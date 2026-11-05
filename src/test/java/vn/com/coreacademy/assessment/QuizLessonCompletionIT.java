package vn.com.coreacademy.assessment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.com.coreacademy.assessment.service.QuizAutoSubmitJob;
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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Đạt đề thì bài quiz của khóa hoàn thành, qua mọi đường chấm; trượt hoặc lần học đã rút thì không đổi. */
class QuizLessonCompletionIT extends AssessmentIntegrationTest {
    private static final String ME = "/api/v1/me";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private QuizAutoSubmitJob autoSubmitJob;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private long competency;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        competency = fixtures.competency();
    }

    /** Khóa đã giao cho học viên, một đề đã xuất bản và một bài quiz bắt buộc trỏ tới đề trong bản đã xuất bản. */
    private record Setup(Course course, long quizId, long quizLessonId, long enrollmentId) {
    }

    private Setup setup(Map<String, Object> quizOverrides) {
        Course course = fixtures.course(LEARNER);
        long single = fixtures.question(course.id(), competency, "SINGLE", 0);
        long multiple = fixtures.question(course.id(), competency, "MULTIPLE", 0, 2);
        Map<String, Object> body = quizBody(course.id());
        body.putAll(quizOverrides);
        return link(course, fixtures.quiz(body, List.of(single, multiple), true));
    }

    /** Bản đã xuất bản không sửa được qua API, nên chèn bài quiz thẳng vào chương của bài nội dung. */
    private Setup link(Course course, long quizId) {
        long chapterId = jdbc.queryForObject("SELECT chapter_id FROM learning_hub.course_lessons WHERE lesson_id = ?",
            Long.class, course.lessonId());
        long quizLessonId = jdbc.queryForObject("""
            INSERT INTO learning_hub.course_lessons (chapter_id, title, lesson_type, estimated_duration_seconds,
                is_required, sort_no, quiz_id, created_by, updated_by)
            VALUES (?, 'Bài kiểm tra cuối', 'quiz', 600, true, 99, ?, ?, ?)
            RETURNING lesson_id
            """, Long.class, chapterId, quizId, INSTRUCTOR, INSTRUCTOR);
        long enrollmentId = jdbc.queryForObject("""
            SELECT enrollment_id FROM learning_hub.course_enrollments WHERE course_id = ? AND user_id = ?
            """, Long.class, course.id(), LEARNER);
        return new Setup(course, quizId, quizLessonId, enrollmentId);
    }

    private void completeContentLesson(Setup s) {
        jdbc.update("""
            INSERT INTO learning_hub.lesson_progress (enrollment_id, lesson_id, status, progress_percent,
                completed_at, created_by, updated_by)
            VALUES (?, ?, 'completed', 100, now(), ?, ?)
            """, s.enrollmentId(), s.course().lessonId(), LEARNER, LEARNER);
    }

    private Setup finalOnly() {
        Course course = fixtures.course(LEARNER);
        long question = fixtures.question(course.id(), competency, "SINGLE", 0);
        Map<String, Object> body = quizBody(course.id());
        body.put("quizType", "FINAL_EXAM");
        body.put("questionCount", 1);
        long quizId = fixtures.quiz(body, List.of(question), true);
        long enrollmentId = jdbc.queryForObject("""
            SELECT enrollment_id FROM learning_hub.course_enrollments WHERE course_id = ? AND user_id = ?
            """, Long.class, course.id(), LEARNER);
        jdbc.update("UPDATE learning_hub.course_completion_rules SET final_quiz_id = ? WHERE revision_id = ?",
            quizId, course.revisionId());
        return new Setup(course, quizId, -1, enrollmentId);
    }

    private JsonNode completeContentThroughApi(Setup s) {
        long blockId = jdbc.queryForObject("SELECT block_id FROM learning_hub.lesson_blocks WHERE lesson_id = ?",
            Long.class, s.course().lessonId());
        JsonNode session = learningCall(api.post(Map.of("enrollmentId", s.enrollmentId(), "blockId", blockId,
            "takeover", true), "/api/v1/me/learning/sessions"));
        return learningCall(api.put(Map.of("sessionId", session.path("sessionId").asText(),
            "sessionFence", session.path("sessionFence").asLong(), "expectedVersion",
            session.path("expectedVersion").asLong(), "sequence", session.path("lastAcceptedSequence").asLong() + 1,
            "eventType", "CLOSE", "complete", true),
            "/api/v1/me/learning/enrollments/{e}/blocks/{b}/progress", s.enrollmentId(), blockId));
    }

    /** Cookie media của phiên học yêu cầu claim auth-version như access token thật. */
    private JsonNode learningCall(MockHttpServletRequestBuilder request) {
        var response = api.raw(null, request.with(jwt().jwt(token -> token.subject(LEARNER)
            .claim(SecurityConstants.AUTH_VERSION_CLAIM, 0L))));
        try {
            JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertThat(response.getStatus()).as(body.toPrettyString()).isEqualTo(200);
            assertThat(body.path("status").asText()).as(body.toPrettyString()).isEqualTo("SUCCESS");
            return body.path("data");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void finalExamNotInTheOutlineStillGatesCourseCompletionUntilPassed() {
        Setup s = finalOnly();
        JsonNode progress = completeContentThroughApi(s).path("enrollment");
        assertThat(progress.path("status").asText()).isEqualTo("in_progress");
        assertThat(progress.path("progressPercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(enrollment(s).get("completed_at")).isNull();
        assertThat(attempt(s, false).path("passed").asBoolean()).isFalse();
        assertThat(enrollment(s).get("status")).isNotEqualTo("completed");
        assertThat(attempt(s, true).path("passed").asBoolean()).isTrue();
        assertThat(enrollment(s)).containsEntry("status", "completed");
        assertThat(enrollment(s).get("completed_at")).isNotNull();
    }

    @Test
    void passingTheFinalExamFirstStillRequiresFinishingTheContent() {
        Setup s = finalOnly();
        attempt(s, true);
        assertThat(enrollment(s).get("status")).isNotEqualTo("completed");
        assertThat(completeContentThroughApi(s).path("enrollment").path("status").asText()).isEqualTo("completed");
    }

    @Test
    void everyRequiredQuizMustPassButOptionalQuizzesDoNotBlockCompletion() {
        Setup first = setup(Map.of());
        long question = fixtures.question(first.course().id(), competency, "SINGLE", 0);
        Map<String, Object> secondBody = quizBody(first.course().id());
        secondBody.put("questionCount", 1);
        long secondId = fixtures.quiz(secondBody, List.of(question), true);
        Setup second = link(first.course(), secondId);
        completeContentLesson(first);
        attempt(first, true);
        assertThat(enrollment(first).get("status")).isNotEqualTo("completed");
        attempt(second, true);
        assertThat(enrollment(first)).containsEntry("status", "completed");

        Setup optional = setup(Map.of());
        jdbc.update("UPDATE learning_hub.course_lessons SET is_required = false WHERE lesson_id = ?",
            optional.quizLessonId());
        assertThat(completeContentThroughApi(optional).path("enrollment").path("status").asText())
            .isEqualTo("completed");
    }

    private Map<String, Object> lesson(Setup s) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT status, progress_percent FROM learning_hub.lesson_progress
            WHERE enrollment_id = ? AND lesson_id = ?
            """, s.enrollmentId(), s.quizLessonId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> enrollment(Setup s) {
        return jdbc.queryForMap("""
            SELECT status, progress_percent, completed_at FROM learning_hub.course_enrollments
            WHERE enrollment_id = ?
            """, s.enrollmentId());
    }

    /** Làm một lượt, chọn đáp án đúng (hoặc bỏ trống) ở câu đầu, rồi nộp; trả kết quả. */
    private JsonNode attempt(Setup s, boolean correct) {
        JsonNode session = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", s.quizId()));
        if (correct) {
            answerFirst(session);
        }
        return api.ok(LEARNER, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit",
            session.path("attemptId").asLong()));
    }

    private void answerFirst(JsonNode session) {
        JsonNode question = session.path("questions").get(0);
        api.ok(LEARNER, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(0).path("id")
            .asLong())), ME + "/quiz-attempts/{a}/questions/{q}", session.path("attemptId").asLong(),
            question.path("attemptQuestionId").asLong()));
    }

    @Test
    void failedAttemptLeavesTheLessonUntouched() {
        Setup s = setup(Map.of());
        completeContentLesson(s);

        JsonNode result = attempt(s, false);

        assertThat(result.path("passed").asBoolean()).isFalse();
        assertThat(lesson(s)).isNull();
        assertThat(enrollment(s).get("status")).isNotEqualTo("completed");
    }

    @Test
    void passingCompletesTheQuizLessonAndTheCourseWhenItIsTheLastRequiredLesson() {
        Setup s = setup(Map.of());
        completeContentLesson(s);

        JsonNode result = attempt(s, true);

        assertThat(result.path("passed").asBoolean()).isTrue();
        assertThat(lesson(s)).containsEntry("status", "completed");
        assertThat((Number) lesson(s).get("progress_percent")).extracting(Number::intValue).isEqualTo(100);
        Map<String, Object> enrolled = enrollment(s);
        assertThat(enrolled).containsEntry("status", "completed");
        assertThat(enrolled.get("completed_at")).isNotNull();
        assertThat(((Number) enrolled.get("progress_percent")).intValue()).isEqualTo(100);
    }

    @Test
    void passingKeepsTheCourseOpenWhileAnotherRequiredLessonIsPending() {
        Setup s = setup(Map.of());

        attempt(s, true);

        assertThat(lesson(s)).containsEntry("status", "completed");
        Map<String, Object> enrolled = enrollment(s);
        assertThat(enrolled.get("status")).isNotEqualTo("completed");
        assertThat(((Number) enrolled.get("progress_percent")).intValue()).isEqualTo(50);
    }

    @Test
    void anExpiredAttemptThatPassesCompletesTheLesson() {
        Setup s = setup(Map.of());
        completeContentLesson(s);
        JsonNode session = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", s.quizId()));
        answerFirst(session);
        jdbc.update("""
            UPDATE learning_hub.quiz_attempts
            SET started_at = now() - interval '30 minutes', deadline_at = now() - interval '1 minute'
            WHERE attempt_id = ?
            """, session.path("attemptId").asLong());

        autoSubmitJob.run();

        assertThat(jdbc.queryForObject("SELECT status FROM learning_hub.quiz_attempts WHERE attempt_id = ?",
            String.class, session.path("attemptId").asLong())).isEqualTo("AUTO_SUBMITTED");
        assertThat(lesson(s)).containsEntry("status", "completed");
        assertThat(enrollment(s)).containsEntry("status", "completed");
    }

    @Test
    void aWithdrawnEnrollmentKeepsTheGradeAndSkipsTheLesson() {
        Setup s = setup(Map.of());
        JsonNode session = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", s.quizId()));
        answerFirst(session);
        long attemptId = session.path("attemptId").asLong();
        jdbc.update("UPDATE learning_hub.course_enrollments SET status = 'withdrawn' WHERE enrollment_id = ?",
            s.enrollmentId());
        jdbc.update("""
            UPDATE learning_hub.quiz_attempts
            SET started_at = now() - interval '30 minutes', deadline_at = now() - interval '1 minute'
            WHERE attempt_id = ?
            """, attemptId);

        autoSubmitJob.run();

        Map<String, Object> graded = jdbc.queryForMap(
            "SELECT status, passed FROM learning_hub.quiz_attempts WHERE attempt_id = ?", attemptId);
        assertThat(graded).containsEntry("status", "AUTO_SUBMITTED").containsEntry("passed", true);
        assertThat(lesson(s)).isNull();
        assertThat(enrollment(s)).containsEntry("status", "withdrawn");
    }

    @Test
    void aQuizThatRequiresLessonsCompletedIsNotBlockedByItsOwnQuizLesson() {
        Setup s = setup(Map.of("requireLessonsCompleted", true));
        JsonNode blocked = api.ok(LEARNER, get(ME + "/quizzes/{id}", s.quizId()));
        assertThat(blocked.path("canStart").asBoolean()).isFalse();
        assertThat(blocked.path("blockReason").asText()).isEqualTo("LESSONS_REQUIRED");

        completeContentLesson(s);

        JsonNode open = api.ok(LEARNER, get(ME + "/quizzes/{id}", s.quizId()));
        assertThat(open.path("canStart").asBoolean()).isTrue();
        assertThat(open.path("blockReason").isNull()).isTrue();
        assertThat(attempt(s, true).path("passed").asBoolean()).isTrue();
    }

    @Test
    void aRegradeThatTurnsFailIntoPassCompletesTheLesson() {
        Course course = fixtures.course(LEARNER);
        long first = fixtures.question(course.id(), competency, "SINGLE", 0);
        long second = fixtures.question(course.id(), competency, "SINGLE", 0);
        Map<String, Object> body = quizBody(course.id());
        body.put("passPercent", 100);
        Setup s = link(course, fixtures.quiz(body, List.of(first, second), true));
        completeContentLesson(s);

        // Câu 1 chọn B (sai), câu 2 chọn A (đúng): 50% nên trượt ở mức đạt 100%.
        JsonNode session = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", s.quizId()));
        for (int i = 0; i < 2; i++) {
            JsonNode question = session.path("questions").get(i);
            api.ok(LEARNER, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(1 - i)
                .path("id").asLong())), ME + "/quiz-attempts/{a}/questions/{q}", session.path("attemptId").asLong(),
                question.path("attemptQuestionId").asLong()));
        }
        JsonNode failed = api.ok(LEARNER, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit",
            session.path("attemptId").asLong()));
        assertThat(failed.path("passed").asBoolean()).isFalse();
        assertThat(lesson(s)).isNull();

        moveKey(first, 1);
        Map<String, Object> request = new HashMap<>();
        request.put("questionId", first);
        request.put("reason", "Sửa đáp án sai");
        request.put("dryRun", false);
        JsonNode regraded = api.ok(INSTRUCTOR, api.post(request, "/api/v1/quizzes/{id}/regrade", s.quizId()));

        assertThat(regraded.path("applied").asInt()).isEqualTo(1);
        assertThat(lesson(s)).containsEntry("status", "completed");
        assertThat(enrollment(s)).containsEntry("status", "completed");
    }

    /** Sửa đáp án đúng tại chỗ (giữ ID phương án) sang chỉ số mới. */
    private void moveKey(long questionId, int correctIndex) {
        JsonNode current = api.ok(INSTRUCTOR, get("/api/v1/questions/{id}", questionId));
        Map<String, Object> body = AssessmentFixtures.questionBody(current.path("courseId").asLong(),
            current.path("competencyId").asLong(), "SINGLE", correctIndex);
        body.put("content", current.path("content").asText());
        List<Map<String, Object>> options = new ArrayList<>();
        for (JsonNode option : current.path("options")) {
            Map<String, Object> value = new HashMap<>();
            value.put("id", option.path("id").asLong());
            value.put("content", option.path("content").asText());
            value.put("correct", option.path("sortNo").asInt() == correctIndex);
            options.add(value);
        }
        body.put("options", options);
        api.ok(INSTRUCTOR, api.put(body, "/api/v1/questions/{id}?expectedVersion={v}", questionId,
            current.path("rowVersion").asLong()));
    }
}
