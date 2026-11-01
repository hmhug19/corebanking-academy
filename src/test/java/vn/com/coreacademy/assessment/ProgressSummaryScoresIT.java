package vn.com.coreacademy.assessment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

import vn.com.coreacademy.learning.service.LearningQuizDirectory;
import vn.com.coreacademy.learning.service.LearningQuizDirectory.QuizScore;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/**
 * progress/summary.assessment qua toàn bộ stack: các con số khớp với điểm cổng quiz trả cho đúng các khóa mà danh sách
 * "khóa của tôi" trả về (cổng đã được QuizScoresIT đối chiếu với AttemptSummary). Không đo số tuyệt đối vì các test
 * khác dùng chung học viên và database.
 */
class ProgressSummaryScoresIT extends AssessmentIntegrationTest {
    private static final String LEARNING = "/api/v1/me/learning";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LearningQuizDirectory directory;

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
    void theOverviewEqualsTheFiguresOfTheQuizzesOfTheMineCourses() {
        Course course = fixtures.course(LEARNER);
        long practice = quiz(course, "PRACTICE");
        long exam = quiz(course, "FINAL_EXAM");
        attempt(practice, 2);
        attempt(exam, 1);
        attempt(exam, 0);

        JsonNode assessment = api.ok(LEARNER, get(LEARNING + "/progress/summary")).path("assessment");

        List<Long> courseIds = new ArrayList<>();
        api.ok(LEARNER, get(LEARNING + "/courses?size=100")).path("items")
            .forEach(card -> courseIds.add(card.path("courseId").asLong()));
        List<QuizScore> scores = directory.scores(LEARNER, courseIds);
        int quizCount = scores.size();
        int passed = (int) scores.stream().filter(QuizScore::passed).count();
        BigDecimal best = scores.stream().map(QuizScore::bestScore).filter(Objects::nonNull)
            .max(BigDecimal::compareTo).orElseThrow();
        List<BigDecimal> finals = scores.stream().map(QuizScore::finalScore).filter(Objects::nonNull).toList();
        BigDecimal finalSum = finals.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        int graded = finals.size();
        assertThat(assessment.path("availability").asText()).isEqualTo("available");
        assertThat(assessment.path("quizCount").asInt()).isEqualTo(quizCount).isGreaterThanOrEqualTo(2);
        assertThat(assessment.path("passedQuizCount").asInt()).isEqualTo(passed);
        assertThat(assessment.path("bestScorePercent").decimalValue()).isEqualByComparingTo(best);
        assertThat(assessment.path("averageScorePercent").decimalValue()).isEqualByComparingTo(
            finalSum.divide(BigDecimal.valueOf(graded), 2, RoundingMode.HALF_UP));
    }

    @Test
    void lastIsTheLatestGradedAttemptOfTheCallerWithItsOwnResult() {
        Course course = fixtures.course(LEARNER);
        long quiz = quiz(course, "PRACTICE");
        attempt(quiz, 2);
        long latest = attempt(quiz, 0);

        JsonNode last = api.ok(LEARNER, get(LEARNING + "/progress/summary")).path("assessment").path("last");

        assertThat(last.path("attemptId").asLong()).isEqualTo(latest);
        assertThat(last.path("quizId").asLong()).isEqualTo(quiz);
        assertThat(last.path("courseId").asLong()).isEqualTo(course.id());
        assertThat(last.path("scorePercent").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(last.path("passed").asBoolean()).isFalse();
        assertThat(last.path("submittedAt").isTextual()).isTrue();
    }

    private long quiz(Course course, String type) {
        List<Long> questions = List.of(fixtures.question(course.id(), competency, "SINGLE", 0),
            fixtures.question(course.id(), competency, "SINGLE", 0));
        Map<String, Object> body = quizBody(course.id());
        body.put("quizType", type);
        body.put("maxAttempts", 5);
        body.put("allowRetakeAfterPass", true);
        return fixtures.quiz(body, questions, true);
    }

    private long attempt(long quizId, int right) {
        JsonNode session = api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        for (int i = 0; i < right; i++) {
            JsonNode question = session.path("questions").get(i);
            api.ok(LEARNER, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(0).path("id")
                    .asLong())), "/api/v1/me/quiz-attempts/{a}/questions/{q}", attemptId,
                question.path("attemptQuestionId").asLong()));
        }
        api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attemptId));
        return attemptId;
    }
}
