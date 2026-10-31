package vn.com.coreacademy.assessment;

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

import static org.assertj.core.api.Assertions.assertThat;
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
