package vn.com.coreacademy.assessment;

import java.nio.charset.StandardCharsets;
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
import vn.com.coreacademy.assessment.service.QuizAutoSubmitJob;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** graded=true của danh sách lượt thi của tôi và của CSV: chỉ SUBMITTED và AUTO_SUBMITTED, kết hợp bộ lọc theo AND. */
class MyAttemptsGradedIT extends AssessmentIntegrationTest {
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

    /** Một lượt mỗi trạng thái trên cùng một đề của LEARNER_2, trả về ID từng lượt. */
    private record Attempts(long quizId, long submitted, long autoSubmitted, long voided, long running) {
    }

    private Attempts attemptsOfEachStatus() {
        Course course = fixtures.course(LEARNER_2);
        Map<String, Object> body = quizBody(course.id());
        body.put("maxAttempts", 6);
        body.put("allowRetakeAfterPass", true);
        long quizId = fixtures.quiz(body, List.of(fixtures.question(course.id(), competency, "SINGLE", 0),
            fixtures.question(course.id(), competency, "SINGLE", 0)), true);

        long submitted = start(quizId);
        api.ok(LEARNER_2, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", submitted));
        long autoSubmitted = start(quizId);
        jdbc.update("""
            UPDATE learning_hub.quiz_attempts
            SET started_at = now() - interval '30 minutes', deadline_at = now() - interval '1 minute'
            WHERE attempt_id = ?
            """, autoSubmitted);
        autoSubmitJob.run();
        long voided = start(quizId);
        api.ok(LEARNER_2, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", voided));
        api.ok(ADMIN, api.post(Map.of("reason", "Gian lận"), "/api/v1/quiz-attempts/{id}/void", voided));
        long running = start(quizId);
        return new Attempts(quizId, submitted, autoSubmitted, voided, running);
    }

    private long start(long quizId) {
        return api.ok(LEARNER_2, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId)).path("attemptId").asLong();
    }

    private List<Long> ids(String query) {
        List<Long> ids = new ArrayList<>();
        api.ok(LEARNER_2, get(ME + "/quiz-attempts?" + query + "&size=100&sort=id,asc")).path("items")
            .forEach(row -> ids.add(row.path("attemptId").asLong()));
        return ids;
    }

    @Test
    void gradedKeepsOnlySubmittedAndAutoSubmittedAttempts() {
        Attempts a = attemptsOfEachStatus();

        assertThat(ids("quizId=" + a.quizId())).as("không lọc").containsExactly(a.submitted(), a.autoSubmitted(),
            a.voided(), a.running());
        assertThat(ids("quizId=" + a.quizId() + "&graded=true")).containsExactly(a.submitted(), a.autoSubmitted());
        assertThat(ids("quizId=" + a.quizId() + "&graded=false")).as("false không lọc")
            .containsExactly(a.submitted(), a.autoSubmitted(), a.voided(), a.running());
        JsonNode page = api.ok(LEARNER_2, get(ME + "/quiz-attempts?quizId={q}&graded=true", a.quizId()));
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        page.path("items").forEach(row -> assertThat(row.path("scorePercent").isNull()).isFalse());
    }

    @Test
    void gradedCombinesWithQuizIdAndStatusByAnd() {
        Attempts a = attemptsOfEachStatus();

        assertThat(ids("quizId=" + a.quizId() + "&graded=true&status=SUBMITTED")).containsExactly(a.submitted());
        assertThat(ids("quizId=" + a.quizId() + "&graded=true&status=AUTO_SUBMITTED"))
            .containsExactly(a.autoSubmitted());
        assertThat(ids("quizId=" + a.quizId() + "&graded=true&status=VOIDED")).isEmpty();
        assertThat(ids("quizId=" + a.quizId() + "&graded=true&status=IN_PROGRESS")).isEmpty();
        assertThat(ids("quizId=" + (a.quizId() + 1_000_000) + "&graded=true")).isEmpty();
        assertThat(ids("graded=true")).contains(a.submitted(), a.autoSubmitted())
            .doesNotContain(a.voided(), a.running());
    }

    @Test
    void gradedStaysScopedToTheCaller() {
        Attempts a = attemptsOfEachStatus();

        JsonNode other = api.ok(LEARNER, get(ME + "/quiz-attempts?quizId={q}&graded=true", a.quizId()));

        assertThat(other.path("totalElements").asLong()).isZero();
    }

    @Test
    void theExportTakesTheSameFilter() throws Exception {
        Attempts a = attemptsOfEachStatus();

        String csv = api.raw(LEARNER_2, get(ME + "/quiz-attempts/export?quizId={q}&graded=true&sort=id,asc",
            a.quizId())).getContentAsString(StandardCharsets.UTF_8);

        List<String> rows = csv.lines().skip(1).toList();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).contains("SUBMITTED").doesNotContain("AUTO_SUBMITTED");
        assertThat(rows.get(1)).contains("AUTO_SUBMITTED");
        String all = api.raw(LEARNER_2, get(ME + "/quiz-attempts/export?quizId={q}", a.quizId()))
            .getContentAsString(StandardCharsets.UTF_8);
        assertThat(all.lines().skip(1).count()).isEqualTo(4);
    }
}
