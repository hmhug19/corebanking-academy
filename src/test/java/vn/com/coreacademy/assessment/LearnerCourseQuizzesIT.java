package vn.com.coreacademy.assessment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.scorm.dto.ScormDtos.RuntimeRequest;
import vn.com.coreacademy.scorm.service.ScormService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Danh sách đề của một khóa cho học viên: thứ tự, trạng thái hiển thị, quyền truy cập và bài quiz gắn kèm. */
class LearnerCourseQuizzesIT extends AssessmentIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ScormService scorm;

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

    private long quiz(Course course, String type, String title, boolean publish) {
        long single = fixtures.question(course.id(), competency, "SINGLE", 0);
        long multiple = fixtures.question(course.id(), competency, "MULTIPLE", 0, 2);
        Map<String, Object> body = quizBody(course.id());
        body.put("quizType", type);
        body.put("title", title);
        return fixtures.quiz(body, List.of(single, multiple), publish);
    }

    private JsonNode list(String user, Course course) {
        return api.ok(user, get("/api/v1/me/courses/{c}/quizzes", course.id()));
    }

    @Test
    void listsPublishedQuizzesWithTheFinalExamLastAndHidesDraftsAndDeleted() {
        Course course = fixtures.course(LEARNER);
        long exam = quiz(course, "FINAL_EXAM", "Thi cuối khóa", true);
        long practiceB = quiz(course, "PRACTICE", "Ôn tập B", true);
        long practiceA = quiz(course, "PRACTICE", "Ôn tập A", true);
        quiz(course, "PRACTICE", "Bản nháp", false);
        long removed = quiz(course, "PRACTICE", "Đã xóa", false);
        api.ok(INSTRUCTOR, ApiClient.delete("/api/v1/quizzes/{id}?expectedVersion={v}", removed,
            fixtures.quizVersion(removed)));

        JsonNode cards = list(LEARNER, course);

        List<Long> ids = new ArrayList<>();
        cards.forEach(card -> ids.add(card.path("quiz").path("id").asLong()));
        assertThat(ids).containsExactly(practiceB, practiceA, exam);
        JsonNode first = cards.get(0);
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("quiz", "summary", "canStart",
            "blockReason", "availableAt", "lessonId");
        assertThat(first.path("quiz").path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(first.path("summary").path("attemptsLeft").asInt()).isEqualTo(3);
        assertThat(first.path("canStart").asBoolean()).isTrue();
        assertThat(first.path("blockReason").isNull()).isTrue();
        assertThat(first.path("lessonId").isNull()).isTrue();
    }

    @Test
    void carriesTheSameBlockAsTheIntroAndTheRunningAttempt() {
        Course course = fixtures.course(LEARNER);
        long quizId = quiz(course, "PRACTICE", "Đang làm", true);
        long locked = fixtures.quiz(lessonsRequired(course), List.of(
            fixtures.question(course.id(), competency, "SINGLE", 0),
            fixtures.question(course.id(), competency, "SINGLE", 0)), true);
        long attemptId = api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId))
            .path("attemptId").asLong();

        JsonNode cards = list(LEARNER, course);

        JsonNode running = card(cards, quizId);
        assertThat(running.path("summary").path("runningAttemptId").asLong()).isEqualTo(attemptId);
        assertThat(running.path("canStart").asBoolean()).isTrue();
        JsonNode blocked = card(cards, locked);
        JsonNode intro = api.ok(LEARNER, get("/api/v1/me/quizzes/{id}", locked));
        assertThat(blocked.path("canStart").asBoolean()).isEqualTo(intro.path("canStart").asBoolean()).isFalse();
        assertThat(blocked.path("blockReason").asText()).isEqualTo(intro.path("blockReason").asText())
            .isEqualTo("LESSONS_REQUIRED");
    }

    @Test
    void fillsLessonIdFromTheQuizLessonOfTheLearnersRevision() {
        Course course = fixtures.course(LEARNER);
        long linked = quiz(course, "FINAL_EXAM", "Thi cuối khóa", true);
        long other = quiz(course, "PRACTICE", "Ôn tập", true);
        long chapterId = jdbc.queryForObject("SELECT chapter_id FROM learning_hub.course_lessons WHERE lesson_id = ?",
            Long.class, course.lessonId());
        long lessonId = jdbc.queryForObject("""
            INSERT INTO learning_hub.course_lessons (chapter_id, title, lesson_type, estimated_duration_seconds,
                is_required, sort_no, quiz_id, created_by, updated_by)
            VALUES (?, 'Bài kiểm tra cuối', 'quiz', 600, true, 99, ?, ?, ?)
            RETURNING lesson_id
            """, Long.class, chapterId, linked, INSTRUCTOR, INSTRUCTOR);

        JsonNode cards = list(LEARNER, course);

        assertThat(card(cards, linked).path("lessonId").asLong()).isEqualTo(lessonId);
        assertThat(card(cards, other).path("lessonId").isNull()).isTrue();
    }

    @Test
    void refusesACallerWithoutAnEnrollmentOfTheCourse() {
        Course course = fixtures.course(LEARNER);
        quiz(course, "PRACTICE", "Ôn tập", true);

        api.error(LEARNER_2, get("/api/v1/me/courses/{c}/quizzes", course.id()), "LEARNING-NOT_AVAILABLE");
    }

    @Test
    void pausedCourseCannotExposeBlocksThroughTheCourseDetailEndpoint() {
        Course course = fixtures.course(LEARNER);
        jdbc.update("update learning_hub.courses set lifecycle_status = 'paused' where course_id = ?", course.id());

        api.error(LEARNER, get("/api/v1/me/learning/courses/{c}", course.id()), "LEARNING-NOT_AVAILABLE");
        api.error(LEARNER, get("/api/v1/me/courses/{c}/quizzes", course.id()), "LEARNING-NOT_AVAILABLE");
    }

    @Test
    void archivedCatalogReturnsAnAvailabilityErrorInsteadOfAnInvalidReference() {
        Course course = fixtures.course(LEARNER);
        Long fieldId = jdbc.queryForObject("""
            select p.field_id from learning_hub.course_revisions r
            join learning_hub.training_programs p on p.program_id = r.program_id where r.revision_id = ?
            """, Long.class, course.revisionId());
        String previous = jdbc.queryForObject(
            "select status from learning_hub.training_fields where field_id = ?", String.class, fieldId);
        jdbc.update("update learning_hub.training_fields set status = 'archived' where field_id = ?", fieldId);
        try {
            api.error(LEARNER, get("/api/v1/me/courses/{c}/quizzes", course.id()),
                "LEARNING-CATALOG_UNAVAILABLE");
            api.error(LEARNER, get("/api/v1/me/learning/courses/{c}", course.id()),
                "LEARNING-CATALOG_UNAVAILABLE");
        } finally {
            jdbc.update("update learning_hub.training_fields set status = ? where field_id = ?", previous, fieldId);
        }
    }

    @Test
    void scormCompletionCountsForTheEnrollmentAndStillRequiresTheFinalExam() {
        Course course = fixtures.course(LEARNER);
        long packageId = jdbc.queryForObject("""
            insert into learning_hub.scorm_packages (owner_org_id, uploaded_by, title, original_name,
                launch_path, files, size_bytes, file_count, published)
            select owner_org_id, owner_user_id, 'SCORM test', 'test.zip', 'index.html', '{}', 1, 1, true
            from learning_hub.courses where course_id = ? returning package_id
            """, Long.class, course.id());
        jdbc.update("update learning_hub.course_lessons set lesson_type = 'scorm', scorm_package_id = ? where lesson_id = ?",
            packageId, course.lessonId());
        long enrollmentId = jdbc.queryForObject("""
            select enrollment_id from learning_hub.course_enrollments where course_id = ? and user_id = ?
            """, Long.class, course.id(), LEARNER);
        long exam = quiz(course, "FINAL_EXAM", "Final exam", true);
        jdbc.update("update learning_hub.course_completion_rules set final_quiz_id = ? where revision_id = ?",
            exam, course.revisionId());
        JsonNode launch = api.ok(LEARNER, api.post(Map.of("preview", false, "enrollmentId", enrollmentId,
            "lessonId", course.lessonId()), "/api/v1/scorm/packages/{id}/launch", packageId));
        String token = launch.path("launchUrl").asText().split("/scorm-player/")[1].split("/")[0];
        scorm.runtime(token, new RuntimeRequest(1,
            Map.of("cmi.core.lesson_status", "completed"), false));
        assertThat(jdbc.queryForObject("select progress_percent from learning_hub.course_enrollments where enrollment_id = ?",
            BigDecimal.class, enrollmentId)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select status from learning_hub.course_enrollments where enrollment_id = ?",
            String.class, enrollmentId)).isEqualTo("in_progress");
        assertThat(api.ok(LEARNER, get("/api/v1/me/learning/enrollments/{id}", enrollmentId))
            .path("completion").path("lessonsSatisfied").asBoolean()).isTrue();
        JsonNode attempt = api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", exam));
        JsonNode question = attempt.path("questions").get(0);
        api.ok(LEARNER, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(0).path("id").asLong())),
            "/api/v1/me/quiz-attempts/{a}/questions/{q}", attempt.path("attemptId").asLong(),
            question.path("attemptQuestionId").asLong()));
        api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attempt.path("attemptId").asLong()));
        assertThat(jdbc.queryForObject("select status from learning_hub.course_enrollments where enrollment_id = ?",
            String.class, enrollmentId)).isEqualTo("completed");
        long second = jdbc.queryForObject("""
            insert into learning_hub.course_enrollments (course_id, revision_id, user_id, cycle_no,
                enrollment_origin, self_enrolled_at, enrolled_at, status, progress_percent, created_by, updated_by)
            select course_id, revision_id, user_id, 2, 'self', now(), now(), 'not_started', 0, created_by, updated_by
            from learning_hub.course_enrollments where enrollment_id = ? returning enrollment_id
            """, Long.class, enrollmentId);
        JsonNode secondLaunch = api.ok(LEARNER, api.post(Map.of("preview", false, "enrollmentId", second,
            "lessonId", course.lessonId()), "/api/v1/scorm/packages/{id}/launch", packageId));
        assertThat(secondLaunch.path("progress").path("lessonStatus").asText()).isEqualTo("not attempted");
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.scorm_registrations where package_id = ? and enrollment_id = 0",
            Long.class, packageId)).isZero();
        jdbc.update("update learning_hub.courses set lifecycle_status = 'paused' where course_id = ?", course.id());
        assertThatThrownBy(() -> scorm.runtime(token,
            new RuntimeRequest(2,
                Map.of("cmi.core.lesson_status", "passed"), true)))
            .isInstanceOf(BusinessException.class);
    }

    private Map<String, Object> lessonsRequired(Course course) {
        Map<String, Object> body = quizBody(course.id());
        body.put("title", "Cần học xong");
        body.put("requireLessonsCompleted", true);
        return body;
    }

    private static JsonNode card(JsonNode cards, long quizId) {
        for (JsonNode card : cards) {
            if (card.path("quiz").path("id").asLong() == quizId) {
                return card;
            }
        }
        throw new AssertionError("Không có đề " + quizId + " trong " + cards.toPrettyString());
    }
}
