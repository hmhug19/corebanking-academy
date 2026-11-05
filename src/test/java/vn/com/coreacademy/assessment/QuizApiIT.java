package vn.com.coreacademy.assessment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

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
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

class QuizApiIT extends AssessmentIntegrationTest {
    private static final String URL = "/api/v1/quizzes";

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

    @Test
    void createStartsAsDraftWithFullConfig() {
        Course course = fixtures.course();
        Map<String, Object> body = quizBody(course.id());
        JsonNode created = api.ok(INSTRUCTOR, api.post(body, URL));

        JsonNode quiz = created.path("quiz");
        assertThat(quiz.path("status").asText()).isEqualTo("DRAFT");
        assertThat(quiz.path("courseId").asLong()).isEqualTo(course.id());
        assertThat(quiz.path("passPercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(quiz.path("publishedAt").isNull()).isTrue();
        for (String field : List.of("selectionMode", "questionCount", "shuffleQuestions", "shuffleOptions",
            "timeLimitMinutes", "openAt", "closeAt", "graceSeconds", "scoringMode", "scoreMethod", "reviewPolicy",
            "maxAttempts", "retakeWaitHours", "allowRetakeAfterPass", "requireLessonsCompleted", "rowVersion")) {
            assertThat(quiz.has(field)).as("thiếu " + field).isTrue();
        }
        assertThat(created.path("pool").isArray()).isTrue();
        assertThat(created.path("activePoolSize").asInt()).isZero();
    }

    @Test
    void configValidationAndSingleFinalExamPerCourse() {
        Course course = fixtures.course();
        Map<String, Object> invalid = quizBody(course.id());
        invalid.put("questionCount", 0);
        invalid.put("passPercent", 120);
        invalid.put("scoringMode", "X");
        JsonNode errors = api.error(INSTRUCTOR, api.post(invalid, URL), "CORE-VALIDATION_FAILED");
        assertThat(errors.path("errors").findValuesAsText("field"))
            .contains("questionCount", "passPercent", "scoringMode");

        Map<String, Object> window = quizBody(course.id());
        window.put("openAt", "2030-01-02T00:00:00Z");
        window.put("closeAt", "2030-01-01T00:00:00Z");
        JsonNode closeAt = api.error(INSTRUCTOR, api.post(window, URL), "CORE-VALIDATION_FAILED");
        assertThat(closeAt.path("errors").get(0).path("field").asText()).isEqualTo("closeAt");

        Map<String, Object> exam = quizBody(course.id());
        exam.put("quizType", "FINAL_EXAM");
        api.ok(INSTRUCTOR, api.post(exam, URL));
        JsonNode duplicate = api.error(INSTRUCTOR, api.post(exam, URL), "ASSESSMENT-DUPLICATE");
        assertThat(duplicate.path("errors").get(0).path("field").asText()).isEqualTo("quizType");
    }

    @Test
    void poolValidationPublishAndListing() {
        Course course = fixtures.course();
        long q1 = fixtures.question(course.id(), competency, "SINGLE", 0);
        long q2 = fixtures.question(course.id(), competency, "MULTIPLE", 0, 1);
        long foreign = fixtures.question(fixtures.course().id(), competency, "SINGLE", 0);
        JsonNode created = api.ok(INSTRUCTOR, api.post(quizBody(course.id()), URL));
        long quizId = created.path("quiz").path("id").asLong();
        long version = created.path("quiz").path("rowVersion").asLong();

        api.error(INSTRUCTOR, api.post(Map.of("expectedVersion", version), URL + "/{id}/publish", quizId),
            "ASSESSMENT-QUIZ_NOT_READY");
        JsonNode dup = api.error(INSTRUCTOR, api.put(pool(q1, q1), URL + "/{id}/questions?expectedVersion={v}",
            quizId, version), "CORE-VALIDATION_FAILED");
        assertThat(dup.path("errors").get(0).path("field").asText()).isEqualTo("items[1].questionId");
        JsonNode other = api.error(INSTRUCTOR, api.put(pool(q1, foreign),
            URL + "/{id}/questions?expectedVersion={v}", quizId, version), "ASSESSMENT-INVALID_REFERENCE");
        assertThat(other.path("errors").get(0).path("field").asText()).isEqualTo("items[1].questionId");
        long[] wholeBank = LongStream.concat(LongStream.of(q1), LongStream.range(1, 2000).map(i -> 900_000_000L + i))
            .toArray();
        JsonNode overLimit = api.error(INSTRUCTOR, api.put(pool(wholeBank),
            URL + "/{id}/questions?expectedVersion={v}", quizId, version), "ASSESSMENT-INVALID_REFERENCE");
        assertThat(overLimit.path("errors").get(0).path("field").asText()).as("2000 câu qua được giới hạn pool")
            .isEqualTo("items[1].questionId");
        api.error(INSTRUCTOR, api.put(pool(LongStream.rangeClosed(1, 2001).toArray()),
            URL + "/{id}/questions?expectedVersion={v}", quizId, version), "CORE-VALIDATION_FAILED");

        JsonNode pooled = api.ok(INSTRUCTOR, api.put(Map.of("items", List.of(
                Map.of("questionId", q1, "sortNo", 0, "points", 2), Map.of("questionId", q2, "sortNo", 1))),
            URL + "/{id}/questions?expectedVersion={v}", quizId, version));
        assertThat(pooled.path("quiz").path("rowVersion").asLong()).isGreaterThan(version);
        assertThat(pooled.path("activePoolSize").asInt()).isEqualTo(2);
        JsonNode entry = pooled.path("pool").get(0);
        assertThat(entry.path("questionId").asLong()).isEqualTo(q1);
        assertThat(entry.path("pointsOverride").decimalValue()).isEqualByComparingTo("2");
        assertThat(entry.path("effectivePoints").decimalValue()).isEqualByComparingTo("2");
        assertThat(pooled.path("pool").get(1).path("pointsOverride").isNull()).isTrue();
        assertThat(pooled.path("pool").get(1).path("effectivePoints").decimalValue()).isEqualByComparingTo("1");
        api.error(INSTRUCTOR, api.put(pool(q1), URL + "/{id}/questions?expectedVersion={v}", quizId, version),
            "ASSESSMENT-CONFLICT");

        long current = pooled.path("quiz").path("rowVersion").asLong();
        JsonNode published = api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", current),
            URL + "/{id}/publish", quizId));
        assertThat(published.path("quiz").path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("quiz").path("publishedAt").isTextual()).isTrue();
        long publishedVersion = published.path("quiz").path("rowVersion").asLong();
        assertThat(api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", publishedVersion), URL + "/{id}/publish",
            quizId)).path("quiz").path("status").asText()).as("publish lặp là idempotent").isEqualTo("PUBLISHED");

        api.error(INSTRUCTOR, api.put(pool(q1), URL + "/{id}/questions?expectedVersion={v}", quizId,
            publishedVersion), "ASSESSMENT-QUIZ_NOT_READY");

        JsonNode page = api.ok(INSTRUCTOR, get(URL + "?courseId={c}&status=PUBLISHED", course.id()));
        assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        JsonNode row = page.path("items").get(0);
        assertThat(row.path("id").asLong()).isEqualTo(quizId);
        assertThat(row.path("poolSize").asLong()).isEqualTo(2);
        assertThat(row.path("gradedAttempts").asLong()).isZero();
        assertThat(row.path("passedAttempts").asLong()).isZero();
        api.error(INSTRUCTOR, get(URL + "?courseId={c}&status=OPEN", course.id()), "CORE-VALIDATION_FAILED");
    }

    @Test
    void updateKeepsPublishedQuizPlayable() {
        Course course = fixtures.course();
        long q1 = fixtures.question(course.id(), competency, "SINGLE", 0);
        long q2 = fixtures.question(course.id(), competency, "SINGLE", 1);
        long quizId = fixtures.quiz(quizBody(course.id()), List.of(q1, q2), true);
        long version = fixtures.quizVersion(quizId);

        Map<String, Object> more = quizBody(course.id());
        more.put("questionCount", 3);
        api.error(INSTRUCTOR, api.put(more, URL + "/{id}?expectedVersion={v}", quizId, version),
            "ASSESSMENT-QUIZ_NOT_READY");

        Map<String, Object> change = quizBody(course.id());
        change.put("title", "Đã đổi tên");
        change.put("passPercent", 80);
        api.error(INSTRUCTOR, api.put(change, URL + "/{id}?expectedVersion={v}", quizId, version + 5),
            "ASSESSMENT-CONFLICT");
        JsonNode updated = api.ok(INSTRUCTOR, api.put(change, URL + "/{id}?expectedVersion={v}", quizId, version));
        assertThat(updated.path("quiz").path("title").asText()).isEqualTo("Đã đổi tên");
        assertThat(updated.path("quiz").path("status").asText()).isEqualTo("PUBLISHED");

        Map<String, Object> move = quizBody(fixtures.course().id());
        api.error(INSTRUCTOR, api.put(move, URL + "/{id}?expectedVersion={v}", quizId,
            updated.path("quiz").path("rowVersion").asLong()), "CORE-VALIDATION_FAILED");
    }

    @Test
    void previewDrawsWithAnswersWithoutCreatingAttempts() {
        Course course = fixtures.course();
        long q1 = fixtures.question(course.id(), competency, "SINGLE", 2);
        long q2 = fixtures.question(course.id(), competency, "MULTIPLE", 0, 3);
        long quizId = fixtures.quiz(quizBody(course.id()), List.of(q1, q2), false);

        JsonNode preview = api.ok(INSTRUCTOR, get(URL + "/{id}/preview", quizId));
        assertThat(preview.path("questionCount").asInt()).isEqualTo(2);
        assertThat(preview.path("maxScore").decimalValue()).isEqualByComparingTo("2");
        JsonNode first = preview.path("questions").get(0);
        assertThat(first.path("displayOrder").asInt()).isEqualTo(1);
        assertThat(first.path("questionId").asLong()).isEqualTo(q1);
        assertThat(first.path("explanation").asText()).isNotBlank();
        assertThat(first.path("options").get(2).path("correct").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.quiz_attempts WHERE quiz_id = ?",
            Integer.class, quizId)).isZero();

        long empty = api.ok(INSTRUCTOR, api.post(quizBody(course.id()), URL)).path("quiz").path("id").asLong();
        api.error(INSTRUCTOR, get(URL + "/{id}/preview", empty), "ASSESSMENT-QUIZ_NOT_READY");
        api.http(LEARNER, get(URL + "/{id}/preview", quizId), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void closeAndDeleteLifecycle() {
        Course course = fixtures.course();
        long q1 = fixtures.question(course.id(), competency, "SINGLE", 0);
        long q2 = fixtures.question(course.id(), competency, "SINGLE", 0);

        long draft = fixtures.quiz(quizBody(course.id()), List.of(q1, q2), false);
        api.error(INSTRUCTOR, api.post(Map.of("expectedVersion", fixtures.quizVersion(draft)),
            URL + "/{id}/close", draft), "ASSESSMENT-CONFLICT");
        api.error(INSTRUCTOR, delete(URL + "/{id}?expectedVersion={v}", draft, 99), "ASSESSMENT-CONFLICT");
        assertThat(api.ok(INSTRUCTOR, delete(URL + "/{id}?expectedVersion={v}", draft, fixtures.quizVersion(draft)))
            .isNull()).isTrue();
        api.error(INSTRUCTOR, get(URL + "/{id}", draft), "ASSESSMENT-NOT_FOUND");

        long live = fixtures.quiz(quizBody(course.id()), List.of(q1, q2), true);
        JsonNode closed = api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", fixtures.quizVersion(live)),
            URL + "/{id}/close", live));
        assertThat(closed.path("quiz").path("status").asText()).isEqualTo("CLOSED");
        JsonNode again = api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion",
            closed.path("quiz").path("rowVersion").asLong()), URL + "/{id}/close", live));
        assertThat(again.path("quiz").path("status").asText()).isEqualTo("CLOSED");
        JsonNode reopened = api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion",
            again.path("quiz").path("rowVersion").asLong()), URL + "/{id}/publish", live));
        assertThat(reopened.path("quiz").path("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void publishRejectsCloseTimeInThePast() {
        Course course = fixtures.course();
        long q1 = fixtures.question(course.id(), competency, "SINGLE", 0);
        long q2 = fixtures.question(course.id(), competency, "SINGLE", 0);
        Map<String, Object> body = quizBody(course.id());
        body.put("closeAt", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        long quizId = fixtures.quiz(body, List.of(q1, q2), false);
        JsonNode error = api.error(INSTRUCTOR, api.post(Map.of("expectedVersion", fixtures.quizVersion(quizId)),
            URL + "/{id}/publish", quizId), "CORE-VALIDATION_FAILED");
        assertThat(error.path("errors").get(0).path("field").asText()).isEqualTo("closeAt");
    }

    private static Map<String, Object> pool(long... questionIds) {
        return Map.of("items", Arrays.stream(questionIds)
            .mapToObj(id -> Map.of("questionId", id, "sortNo", 0)).toList());
    }
}
