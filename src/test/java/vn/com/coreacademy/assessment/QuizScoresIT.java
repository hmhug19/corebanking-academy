package vn.com.coreacademy.assessment;

import java.math.BigDecimal;
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
import vn.com.coreacademy.learning.service.LearningQuizDirectory;
import vn.com.coreacademy.learning.service.LearningQuizDirectory.QuizScore;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Cổng điểm của Learning dùng chung quy tắc tổng hợp với màn giới thiệu đề, nên hai nơi phải cho cùng con số. */
class QuizScoresIT extends AssessmentIntegrationTest {
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
    void aBestQuizScoresLikeItsIntroIgnoringVoidedAndRunningAttempts() {
        Course course = fixtures.course(LEARNER);
        long quiz = quiz(course, "PRACTICE", "BEST");
        attempt(LEARNER, quiz, 0);
        long secondId = attempt(LEARNER, quiz, 1);
        long voidedId = attempt(LEARNER, quiz, 2);
        api.ok(ADMIN, api.post(Map.of("reason", "Gian lận"), "/api/v1/quiz-attempts/{id}/void", voidedId));
        api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quiz));

        QuizScore score = scoreOf(LEARNER, course, quiz);

        JsonNode summary = intro(LEARNER, quiz);
        assertThat(score.bestScore()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(score.finalScore()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(score.passed()).isTrue();
        assertSameAsIntro(score, summary);
        assertThat(score.last().attemptId()).as("lượt chấm gần nhất, không phải lượt VOIDED hay đang làm")
            .isEqualTo(secondId);
        assertThat(score.last().scorePercent()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(score.last().passed()).isTrue();
        assertThat(score.last().submittedAt()).isNotNull();
    }

    @Test
    void aLastQuizScoresByTheLatestGradedAttemptWhileTheBestScoreStaysHigh() {
        Course course = fixtures.course(LEARNER);
        long quiz = quiz(course, "FINAL_EXAM", "LAST");
        attempt(LEARNER, quiz, 2);
        long latestId = attempt(LEARNER, quiz, 0);

        QuizScore score = scoreOf(LEARNER, course, quiz);

        assertThat(score.bestScore()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(score.finalScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(score.passed()).as("LAST: lượt chấm gần nhất không đạt").isFalse();
        assertSameAsIntro(score, intro(LEARNER, quiz));
        assertThat(score.quizType()).isEqualTo("FINAL_EXAM");
        assertThat(score.last().attemptId()).isEqualTo(latestId);
        assertThat(score.last().passed()).isFalse();
    }

    @Test
    void aQuizWithoutAGradedAttemptHasNullScoresAndNoLastAttempt() {
        Course course = fixtures.course(LEARNER);
        long quiz = quiz(course, "PRACTICE", "BEST");
        api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quiz));

        QuizScore score = scoreOf(LEARNER, course, quiz);

        assertThat(score.bestScore()).isNull();
        assertThat(score.finalScore()).isNull();
        assertThat(score.passed()).isFalse();
        assertThat(score.last()).isNull();
        assertSameAsIntro(score, intro(LEARNER, quiz));
    }

    @Test
    void scoresBelongToTheCallerAndCoverOnlyPublishedQuizzesOfTheAskedCourses() {
        Course first = fixtures.course(LEARNER, LEARNER_2);
        Course second = fixtures.course(LEARNER, LEARNER_2);
        Course other = fixtures.course(LEARNER);
        long firstQuiz = quiz(first, "PRACTICE", "BEST");
        long secondQuiz = quiz(second, "FINAL_EXAM", "BEST");
        long draft = fixtures.quiz(body(second, "PRACTICE", "BEST"), questions(second), false);
        long removed = quiz(second, "PRACTICE", "BEST");
        api.ok(AssessmentFixtures.INSTRUCTOR, ApiClient.delete("/api/v1/quizzes/{id}?expectedVersion={v}", removed,
            fixtures.quizVersion(removed)));
        long otherQuiz = quiz(other, "PRACTICE", "BEST");
        attempt(LEARNER, firstQuiz, 2);
        attempt(LEARNER_2, secondQuiz, 1);

        List<QuizScore> mine = directory.scores(LEARNER, List.of(first.id(), second.id()));

        assertThat(mine).extracting(QuizScore::quizId).containsExactly(firstQuiz, secondQuiz)
            .doesNotContain(draft, removed, otherQuiz);
        assertThat(mine).extracting(QuizScore::courseId).containsExactly(first.id(), second.id());
        assertThat(mine.get(0).bestScore()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(mine.get(1).bestScore()).as("điểm của học viên khác không lẫn vào").isNull();
        assertThat(directory.scores(LEARNER_2, List.of(second.id())).get(0).bestScore())
            .isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    @Test
    void noCoursesMeansNoScores() {
        assertThat(directory.scores(LEARNER, List.of())).isEmpty();
    }

    private QuizScore scoreOf(String user, Course course, long quizId) {
        return directory.scores(user, List.of(course.id())).stream().filter(score -> score.quizId() == quizId)
            .findFirst().orElseThrow();
    }

    private JsonNode intro(String user, long quizId) {
        return api.ok(user, get("/api/v1/me/quizzes/{id}", quizId)).path("summary");
    }

    private static void assertSameAsIntro(QuizScore score, JsonNode summary) {
        assertThat(decimal(summary.path("bestScore"))).isEqualTo(score.bestScore() == null ? null
            : score.bestScore().stripTrailingZeros());
        assertThat(decimal(summary.path("finalScore"))).isEqualTo(score.finalScore() == null ? null
            : score.finalScore().stripTrailingZeros());
        assertThat(summary.path("passed").asBoolean()).isEqualTo(score.passed());
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isNull() ? null : node.decimalValue().stripTrailingZeros();
    }

    private Map<String, Object> body(Course course, String type, String method) {
        Map<String, Object> body = quizBody(course.id());
        body.put("quizType", type);
        body.put("scoreMethod", method);
        body.put("maxAttempts", 5);
        body.put("allowRetakeAfterPass", true);
        return body;
    }

    private List<Long> questions(Course course) {
        return List.of(fixtures.question(course.id(), competency, "SINGLE", 0),
            fixtures.question(course.id(), competency, "SINGLE", 0));
    }

    /** Đề hai câu, mỗi câu đúng ở phương án đầu, đạt từ 50%; xuất bản. */
    private long quiz(Course course, String type, String method) {
        return fixtures.quiz(body(course, type, method), questions(course), true);
    }

    /** Một lượt thi: trả lời đúng {@code right} câu đầu, bỏ trống phần còn lại, rồi nộp. Trả attemptId. */
    private long attempt(String user, long quizId, int right) {
        JsonNode session = api.ok(user, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        for (int i = 0; i < right; i++) {
            JsonNode question = session.path("questions").get(i);
            api.ok(user, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(0).path("id")
                    .asLong())), "/api/v1/me/quiz-attempts/{a}/questions/{q}", attemptId,
                question.path("attemptQuestionId").asLong()));
        }
        api.ok(user, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attemptId));
        return attemptId;
    }
}
