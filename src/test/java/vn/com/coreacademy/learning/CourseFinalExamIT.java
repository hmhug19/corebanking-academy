package vn.com.coreacademy.learning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.security.SecurityConstants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Bài kiểm tra cuối khóa: lưu, đọc lại, sao chép, xuất bản và điều kiện hoàn thành khóa. */
class CourseFinalExamIT extends AssessmentIntegrationTest {
    private static final String REVISION = "/api/v1/courses/{c}/revisions/{r}";
    private static final String LEARNING = "/api/v1/me/learning";
    private static final String ME = "/api/v1/me";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private long competency;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        competency = fixtures.competency();
    }

    private long quiz(Course course, String type, boolean publish) {
        long single = fixtures.question(course.id(), competency, "SINGLE", 0);
        long multiple = fixtures.question(course.id(), competency, "MULTIPLE", 0, 2);
        Map<String, Object> body = quizBody(course.id());
        body.put("quizType", type);
        return fixtures.quiz(body, List.of(single, multiple), publish);
    }

    private JsonNode newDraft(Course course) {
        return api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/courses/{c}/draft", course.id()));
    }

    private Map<String, Object> textLesson() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("blockType", "text");
        block.put("title", "Giới thiệu");
        block.put("body", "Nội dung bài học.");
        block.put("bodyFormat", "markdown");
        block.put("completionMode", "manual");
        block.put("completionThreshold", null);
        block.put("isRequired", true);
        block.put("assets", List.of());
        block.put("steps", List.of());
        Map<String, Object> lesson = new LinkedHashMap<>();
        lesson.put("title", "Bài nội dung");
        lesson.put("lessonType", "content");
        lesson.put("estimatedDurationSeconds", 300);
        lesson.put("isRequired", true);
        lesson.put("quizId", null);
        lesson.put("blocks", List.of(block));
        return lesson;
    }

    /** Thân PUT nháp; {@code finalQuizId} chỉ có mặt khi khác null để thử máy khách cũ không gửi trường này. */
    private Map<String, Object> draftBody(JsonNode detail, Long finalQuizId) {
        JsonNode revision = detail.path("revision");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", revision.path("rowVersion").asLong());
        body.put("programId", revision.path("programId").asLong());
        body.put("moduleId", revision.path("moduleId").asLong());
        body.put("title", revision.path("title").asText());
        body.put("summary", "Khóa kiểm thử bài thi cuối khóa.");
        body.put("level", "basic");
        body.put("defaultRequirement", "optional");
        body.put("coverAssetId", null);
        body.put("coverIcon", null);
        body.put("coverColor", null);
        body.put("objectives", List.of("Mục tiêu"));
        body.put("chapters", List.of(Map.of("title", "Chương 1", "lessons", new ArrayList<>(List.of(textLesson())))));
        body.put("rewardPoints", 0);
        if (finalQuizId != null) {
            body.put("finalQuizId", finalQuizId);
        }
        return body;
    }

    private JsonNode save(JsonNode detail, Course course, Long finalQuizId) {
        return api.ok(INSTRUCTOR, api.put(draftBody(detail, finalQuizId), REVISION, course.id(),
            detail.path("revision").path("id").asLong()));
    }

    private MockHttpServletRequestBuilder publishRequest(JsonNode saved, Course course) {
        return api.post(Map.of("expectedVersion", saved.path("revision").path("rowVersion").asLong()),
            REVISION + "/publish", course.id(), saved.path("revision").path("id").asLong());
    }

    private JsonNode publish(JsonNode saved, Course course) {
        return api.ok(INSTRUCTOR, publishRequest(saved, course));
    }

    private void publishQuiz(long quizId) {
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", fixtures.quizVersion(quizId)),
            "/api/v1/quizzes/{id}/publish", quizId));
    }

    private List<Map<String, Object>> finalRows(long revisionId) {
        return jdbc.queryForList("""
            SELECT placement, assessment_revision_id, lesson_id, is_required, pass_score_percent, max_attempts,
                   allow_retake, opens_at, closes_at, sort_no
            FROM learning_hub.course_assessments WHERE revision_id = ?
            """, revisionId);
    }

    /** Khóa mới (chưa giao ai) có bản 2 đã xuất bản kèm bài thi cuối khóa, giao cho các học viên. */
    private record WithExam(Course course, long quizId, long revisionId) {
    }

    private WithExam publishedWithExam(String... learners) {
        Course course = fixtures.course();
        long quizId = quiz(course, "FINAL_EXAM", true);
        JsonNode published = publish(save(newDraft(course), course, quizId), course);
        long revisionId = published.path("revision").path("id").asLong();
        if (learners.length > 0) {
            fixtures.assign(course.id(), revisionId, learners);
        }
        return new WithExam(course, quizId, revisionId);
    }

    private long enrollmentOf(long courseId, String user) {
        return jdbc.queryForObject("""
            SELECT enrollment_id FROM learning_hub.course_enrollments WHERE course_id = ? AND user_id = ?
            """, Long.class, courseId, user);
    }

    private long blockOf(long revisionId) {
        return jdbc.queryForObject("""
            SELECT b.block_id FROM learning_hub.lesson_blocks b
            JOIN learning_hub.course_lessons l ON l.lesson_id = b.lesson_id
            JOIN learning_hub.course_chapters c ON c.chapter_id = l.chapter_id
            WHERE c.revision_id = ?
            """, Long.class, revisionId);
    }

    private String status(long enrollmentId) {
        return jdbc.queryForObject("SELECT status FROM learning_hub.course_enrollments WHERE enrollment_id = ?",
            String.class, enrollmentId);
    }

    /** Học viên mở bài rồi bấm hoàn thành khối qua API tiến độ, nên khóa được tính lại như thật. */
    private void finishLesson(String user, long enrollmentId, long blockId) {
        JsonNode session = asUser(user, api.post(Map.of("enrollmentId", enrollmentId, "blockId", blockId,
            "takeover", true), LEARNING + "/sessions"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sessionId", session.path("sessionId").asText());
        request.put("sessionFence", session.path("sessionFence").asLong());
        request.put("eventType", "COMPLETE");
        request.put("playing", false);
        request.put("expectedVersion", session.path("version").asLong());
        request.put("sequence", 1);
        request.put("complete", true);
        asUser(user, api.put(request, LEARNING + "/enrollments/{e}/blocks/{b}/progress", enrollmentId, blockId));
    }

    /** /sessions và progress ký cookie media từ claim av của JWT, nên token giả lập phải mang claim này. */
    private JsonNode asUser(String user, MockHttpServletRequestBuilder request) {
        MockHttpServletResponse response = api.raw(null, request.with(jwt().jwt(token -> token.subject(user)
            .claim(SecurityConstants.AUTH_VERSION_CLAIM, 0L))));
        try {
            JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertThat(body.path("status").asText()).as(body.toPrettyString()).isEqualTo("SUCCESS");
            return body.path("data");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Làm một lượt thi, trả lời đúng câu đầu (đủ qua mức đạt 50%) hoặc bỏ trống, rồi nộp. */
    private JsonNode attempt(String user, long quizId, boolean correct) {
        JsonNode session = api.ok(user, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        if (correct) {
            JsonNode question = session.path("questions").get(0);
            api.ok(user, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(0).path("id")
                .asLong())), ME + "/quiz-attempts/{a}/questions/{q}", session.path("attemptId").asLong(),
                question.path("attemptQuestionId").asLong()));
        }
        return api.ok(user, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit",
            session.path("attemptId").asLong()));
    }

    @Test
    void savesTheFinalExamAndReadsItBack() {
        Course course = fixtures.course();
        long quizId = quiz(course, "FINAL_EXAM", true);
        JsonNode draft = newDraft(course);
        assertThat(draft.path("finalQuizId").isNull()).isTrue();

        JsonNode saved = save(draft, course, quizId);
        JsonNode again = save(saved, course, quizId);

        assertThat(saved.path("finalQuizId").asLong()).isEqualTo(quizId);
        long revisionId = again.path("revision").path("id").asLong();
        assertThat(again.path("finalQuizId").asLong()).isEqualTo(quizId);
        assertThat(api.ok(INSTRUCTOR, get("/api/v1/courses/{c}?revisionId={r}", course.id(), revisionId)).path("finalQuizId").asLong())
            .isEqualTo(quizId);
        assertThat(finalRows(revisionId)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("placement", "final").containsEntry("assessment_revision_id", quizId)
                .containsEntry("is_required", true).containsEntry("allow_retake", false)
                .containsEntry("sort_no", 0);
            assertThat(row.get("lesson_id")).isNull();
            assertThat(row.get("opens_at")).isNull();
            assertThat(row.get("closes_at")).isNull();
            assertThat(((Number) row.get("pass_score_percent")).intValue()).isEqualTo(50);
            assertThat(row.get("max_attempts")).isEqualTo(
                jdbc.queryForObject("SELECT max_attempts FROM learning_hub.quizzes WHERE quiz_id = ?",
                    Integer.class, quizId));
        });
    }

    @Test
    void acceptsADraftFinalExamAtSaveTime() {
        Course course = fixtures.course();
        long draftQuiz = quiz(course, "FINAL_EXAM", false);

        JsonNode saved = save(newDraft(course), course, draftQuiz);

        assertThat(saved.path("finalQuizId").asLong()).isEqualTo(draftQuiz);
    }

    @Test
    void refusesAQuizThatCannotBeTheFinalExam() {
        Course course = fixtures.course();
        Course other = fixtures.course();
        long practice = quiz(course, "PRACTICE", true);
        long foreign = quiz(other, "FINAL_EXAM", true);
        long deleted = quiz(course, "FINAL_EXAM", false);
        api.ok(INSTRUCTOR, ApiClient.delete("/api/v1/quizzes/{id}?expectedVersion={v}", deleted,
            fixtures.quizVersion(deleted)));
        JsonNode draft = newDraft(course);
        long revisionId = draft.path("revision").path("id").asLong();

        for (long quizId : new long[] {practice, foreign, deleted, 987654321L}) {
            JsonNode error = api.error(INSTRUCTOR, api.put(draftBody(draft, quizId), REVISION, course.id(),
                revisionId), "LEARNING-QUIZ_INVALID");
            assertThat(error.path("errors").get(0).path("field").asText()).isEqualTo("finalQuizId");
        }
        assertThat(finalRows(revisionId)).isEmpty();
    }

    @Test
    void aSaveWithoutTheFieldClearsTheFinalExam() {
        Course course = fixtures.course();
        long quizId = quiz(course, "FINAL_EXAM", true);
        JsonNode saved = save(newDraft(course), course, quizId);
        long revisionId = saved.path("revision").path("id").asLong();
        assertThat(finalRows(revisionId)).hasSize(1);

        JsonNode cleared = save(saved, course, null);

        assertThat(cleared.path("finalQuizId").isNull()).isTrue();
        assertThat(finalRows(revisionId)).isEmpty();
    }

    @Test
    void theRevisionCopyKeepsTheFinalExamButACourseCopyDoesNot() {
        WithExam exam = publishedWithExam();

        JsonNode next = newDraft(exam.course());

        assertThat(next.path("revision").path("id").asLong()).isNotEqualTo(exam.revisionId());
        assertThat(next.path("finalQuizId").asLong()).isEqualTo(exam.quizId());
        assertThat(finalRows(next.path("revision").path("id").asLong())).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("assessment_revision_id", exam.quizId())
                .containsEntry("placement", "final"));
        JsonNode copy = api.ok(INSTRUCTOR, api.post(Map.of("code", AssessmentFixtures.unique("COPY")),
            "/api/v1/courses/{c}/copy", exam.course().id()));
        assertThat(copy.path("finalQuizId").isNull()).isTrue();
    }

    @Test
    void publishIsRefusedWhileTheFinalExamIsADraftAndAcceptedOnceItIsPublished() {
        Course course = fixtures.course();
        long quizId = quiz(course, "FINAL_EXAM", false);
        JsonNode saved = save(newDraft(course), course, quizId);

        api.error(INSTRUCTOR, publishRequest(saved, course), "LEARNING-FINAL_EXAM_UNPUBLISHED");
        publishQuiz(quizId);
        JsonNode published = publish(saved, course);

        assertThat(published.path("revision").path("state").asText()).isEqualTo("published");
        assertThat(published.path("finalQuizId").asLong()).isEqualTo(quizId);
    }

    @Test
    void publishIsRefusedWhenThePublishedFinalExamWasDeletedMeanwhile() {
        Course course = fixtures.course();
        long quizId = quiz(course, "FINAL_EXAM", true);
        JsonNode saved = save(newDraft(course), course, quizId);
        jdbc.update("UPDATE learning_hub.quizzes SET is_deleted = true WHERE quiz_id = ?", quizId);

        api.error(INSTRUCTOR, publishRequest(saved, course), "LEARNING-FINAL_EXAM_UNPUBLISHED");
    }

    @Test
    void lessonsWithoutTheExamStayInProgressThenPassingTheExamCompletes() {
        WithExam exam = publishedWithExam(LEARNER);
        jdbc.update("UPDATE learning_hub.course_completion_rules SET final_quiz_id = null WHERE revision_id = ?",
            exam.revisionId());
        api.error(INSTRUCTOR, ApiClient.delete("/api/v1/quizzes/{id}?expectedVersion={v}", exam.quizId(),
            fixtures.quizVersion(exam.quizId())), "ASSESSMENT-QUIZ_IN_USE");
        long enrollment = enrollmentOf(exam.course().id(), LEARNER);

        finishLesson(LEARNER, enrollment, blockOf(exam.revisionId()));
        assertThat(status(enrollment)).isNotEqualTo("completed");
        JsonNode detail = asUser(LEARNER, get(LEARNING + "/enrollments/{id}", enrollment));
        assertThat(detail.path("enrollment").path("progressPercent").decimalValue())
            .isEqualByComparingTo("100");
        assertThat(detail.path("completion").path("lessonsSatisfied").asBoolean()).isTrue();
        assertThat(detail.path("completion").path("finalExamStatus").asText()).isEqualTo("NOT_TAKEN");
        assertThat(detail.path("completion").path("allSatisfied").asBoolean()).isFalse();
        assertThat(attempt(LEARNER, exam.quizId(), false).path("passed").asBoolean()).isFalse();
        assertThat(status(enrollment)).isNotEqualTo("completed");
        assertThat(asUser(LEARNER, get(LEARNING + "/enrollments/{id}", enrollment))
            .path("completion").path("finalExamStatus").asText()).isEqualTo("NOT_PASSED");

        assertThat(attempt(LEARNER, exam.quizId(), true).path("passed").asBoolean()).isTrue();

        assertThat(status(enrollment)).isEqualTo("completed");
        assertThat(asUser(LEARNER, get(LEARNING + "/enrollments/{id}", enrollment))
            .path("completion").path("allSatisfied").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT completed_at FROM learning_hub.course_enrollments"
            + " WHERE enrollment_id = ?", Object.class, enrollment)).isNotNull();
    }

    @Test
    void passingTheExamFirstThenTheLastLessonCompletes() {
        WithExam exam = publishedWithExam(LEARNER);
        long enrollment = enrollmentOf(exam.course().id(), LEARNER);

        assertThat(attempt(LEARNER, exam.quizId(), true).path("passed").asBoolean()).isTrue();
        assertThat(status(enrollment)).isNotEqualTo("completed");

        finishLesson(LEARNER, enrollment, blockOf(exam.revisionId()));

        assertThat(status(enrollment)).isEqualTo("completed");
    }

    @Test
    void aRevisionWithoutAFinalExamCompletesAsBefore() {
        Course course = fixtures.course(LEARNER);
        long enrollment = enrollmentOf(course.id(), LEARNER);

        finishLesson(LEARNER, enrollment, blockOf(course.revisionId()));

        assertThat(status(enrollment)).isEqualTo("completed");
    }

    @Test
    void anEnrollmentOnAnOlderRevisionIsNotAffectedByANewerFinalExam() {
        Course course = fixtures.course(LEARNER);
        long enrollment = enrollmentOf(course.id(), LEARNER);
        long quizId = quiz(course, "FINAL_EXAM", true);
        JsonNode published = publish(save(newDraft(course), course, quizId), course);
        fixtures.assign(course.id(), published.path("revision").path("id").asLong(), LEARNER_2);
        long newer = enrollmentOf(course.id(), LEARNER_2);

        finishLesson(LEARNER, enrollment, blockOf(course.revisionId()));
        finishLesson(LEARNER_2, newer, blockOf(published.path("revision").path("id").asLong()));

        assertThat(status(enrollment)).isEqualTo("completed");
        assertThat(status(newer)).isNotEqualTo("completed");
    }
}
