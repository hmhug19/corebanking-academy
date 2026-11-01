package vn.com.coreacademy.learning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import vn.com.coreacademy.learning.service.LearningActivityService;
import vn.com.coreacademy.security.SecurityConstants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Ngày học được ghi đúng một lần mỗi ngày từ bốn hành động của học viên và không bao giờ làm hỏng chúng. */
class LearningActivityIT extends AssessmentIntegrationTest {
    private static final String LEARNING = "/api/v1/me/learning";
    private static final String ME = "/api/v1/me";
    private static final String DAYS = "learning_hub.learning_activity_days";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LearningActivityService activity;

    private ApiClient api;
    private Course course;
    private long enrollmentId;
    private long blockId;
    private long quizId;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        course = fixtures.course(LEARNER, LEARNER_2);
        enrollmentId = jdbc.queryForObject("""
            select enrollment_id from learning_hub.course_enrollments where course_id = ? and user_id = ?
            """, Long.class, course.id(), LEARNER);
        blockId = jdbc.queryForObject("select block_id from learning_hub.lesson_blocks where lesson_id = ?",
            Long.class, course.lessonId());
        long competency = fixtures.competency();
        quizId = fixtures.quiz(quizBody(course.id()), List.of(fixtures.question(course.id(), competency, "SINGLE", 0),
            fixtures.question(course.id(), competency, "SINGLE", 1)), true);
    }

    @BeforeEach
    void noDaysYet() {
        jdbc.update("delete from " + DAYS);
    }

    @Test
    void openingALessonWritesTodayOnce() {
        session();
        session();

        assertThat(rows(LEARNER)).singleElement().satisfies(row -> {
            assertThat(row.get("activity_date").toString())
                .isEqualTo(LearningActivityService.localDate(Instant.now()).toString());
            assertThat(row.get("first_source")).isEqualTo("session");
        });
        assertThat(rows(LEARNER_2)).isEmpty();
    }

    @Test
    void sendingBlockProgressWritesTodayOnce() {
        JsonNode session = session();
        jdbc.update("delete from " + DAYS);

        Map<String, Object> request = Map.of("sessionId", session.path("sessionId").asText(),
            "sessionFence", session.path("sessionFence").asLong(), "eventType", "OPEN", "playing", false,
            "expectedVersion", session.path("version").asLong(), "sequence", 1, "complete", false);
        withVersion(api.put(request, LEARNING + "/enrollments/{e}/blocks/{b}/progress", enrollmentId, blockId));

        assertThat(rows(LEARNER)).singleElement()
            .satisfies(row -> assertThat(row.get("first_source")).isEqualTo("block_progress"));
    }

    @Test
    void startingAndSubmittingAQuizWriteTodayOnce() {
        JsonNode attempt = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        assertThat(rows(LEARNER)).singleElement()
            .satisfies(row -> assertThat(row.get("first_source")).isEqualTo("quiz"));

        api.ok(LEARNER, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", attempt.path("attemptId").asLong()));
        assertThat(rows(LEARNER)).hasSize(1);
    }

    @Test
    void aRefusedActionLeavesNoDay() throws Exception {
        MockHttpServletResponse response = api.raw(null, api.post(Map.of("enrollmentId", enrollmentId,
            "blockId", blockId), LEARNING + "/sessions").with(jwt().jwt(token -> token.subject(LEARNER_2)
            .claim(SecurityConstants.AUTH_VERSION_CLAIM, 0L))));

        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("\"status\":\"ERROR\"");
        assertThat(rows(LEARNER)).isEmpty();
        assertThat(rows(LEARNER_2)).isEmpty();
    }

    @Test
    void concurrentCallsMakeOneRow() throws Exception {
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                done.add(pool.submit(() -> {
                    go.await();
                    activity.record(LEARNER_2, "session");
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> future : done) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rows(LEARNER_2)).hasSize(1);
    }

    @Test
    void halfPastElevenAndHalfPastMidnightLocalAreDifferentDays() {
        activity.record(LEARNER, "session", Instant.parse("2026-10-30T16:30:00Z"));
        activity.record(LEARNER, "session", Instant.parse("2026-10-30T16:59:59Z"));
        activity.record(LEARNER, "session", Instant.parse("2026-10-30T17:30:00Z"));

        assertThat(jdbc.queryForList("select activity_date::text from " + DAYS + " order by activity_date",
            String.class)).containsExactly("2026-10-30", "2026-10-31");
    }

    @Test
    void aFailingRecorderStillReturnsSuccessToTheLearner() {
        jdbc.execute("alter table " + DAYS + " rename to learning_activity_days_hidden");
        try {
            session();
            api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
            api.ok(LEARNER, get(LEARNING + "/courses/{id}", course.id()));
        } finally {
            jdbc.execute("alter table learning_hub.learning_activity_days_hidden rename to learning_activity_days");
        }

        assertThat(rows(LEARNER)).isEmpty();
    }

    private JsonNode session() {
        return withVersion(api.post(Map.of("enrollmentId", enrollmentId, "blockId", blockId, "takeover", true),
            LEARNING + "/sessions"));
    }

    /** /sessions và progress ký cookie media từ claim av của JWT, nên token giả lập phải mang claim này. */
    private JsonNode withVersion(MockHttpServletRequestBuilder request) {
        MockHttpServletResponse response = api.raw(null, request.with(jwt().jwt(token -> token.subject(LEARNER)
            .claim(SecurityConstants.AUTH_VERSION_CLAIM, 0L))));
        try {
            JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertThat(body.path("status").asText()).as(body.toPrettyString()).isEqualTo("SUCCESS");
            return body.path("data");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private List<Map<String, Object>> rows(String user) {
        return jdbc.queryForList("select * from " + DAYS + " where user_id = ?", user);
    }
}
