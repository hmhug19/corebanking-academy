package vn.com.coreacademy.assessment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.learning.service.LearningQuizDirectory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** CourseCard.bestScorePercent là điểm cao nhất trên bài thi cuối khóa, điền một lần cho cả trang, và cột CSV cuối. */
class CourseCardScoreIT extends AssessmentIntegrationTest {
    private static final String LEARNING = "/api/v1/me/learning";

    @MockitoSpyBean
    private LearningQuizDirectory directory;

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
    void theCardCarriesTheBestFinalExamScoreOnEveryLearnerList() {
        Course course = fixtures.course(LEARNER, LEARNER_2);
        long exam = quiz(course, "FINAL_EXAM");
        attempt(LEARNER, exam, 1);
        attempt(LEARNER, exam, 2);
        attempt(LEARNER, exam, 0);

        for (String list : List.of("/courses", "/catalog")) {
            assertThat(card(LEARNER, list, course).path("bestScorePercent").decimalValue())
                .as(list).isEqualByComparingTo("100");
        }
        JsonNode single = api.ok(LEARNER, get(LEARNING + "/courses/{c}", course.id())).path("card");
        assertThat(single.path("bestScorePercent").decimalValue()).isEqualByComparingTo("100");
        assertThat(card(LEARNER_2, "/courses", course).path("bestScorePercent").isNull())
            .as("điểm của học viên khác không lẫn vào").isTrue();
    }

    @Test
    void thePracticeQuizesAndMissingQuizesGiveNull() {
        Course practiceOnly = fixtures.course(LEARNER);
        attempt(LEARNER, quiz(practiceOnly, "PRACTICE"), 2);
        Course none = fixtures.course(LEARNER);
        Course notTaken = fixtures.course(LEARNER);
        quiz(notTaken, "FINAL_EXAM");

        assertThat(card(LEARNER, "/courses", practiceOnly).has("bestScorePercent")).isTrue();
        assertThat(card(LEARNER, "/courses", practiceOnly).path("bestScorePercent").isNull())
            .as("chỉ tính bài thi cuối khóa").isTrue();
        assertThat(card(LEARNER, "/courses", none).path("bestScorePercent").isNull()).isTrue();
        assertThat(card(LEARNER, "/courses", notTaken).path("bestScorePercent").isNull()).isTrue();
    }

    @Test
    void aPageCostsOneEnrollmentScoresCall() {
        fixtures.course(LEARNER);
        fixtures.course(LEARNER);
        Mockito.clearInvocations(directory);

        JsonNode page = api.ok(LEARNER, get(LEARNING + "/courses?size=100"));

        assertThat(page.path("items").size()).isGreaterThanOrEqualTo(2);
        verify(directory, times(1)).enrollmentScores(any(), any());
    }

    @Test
    void theInstructorListNeverCallsThePortAndLeavesTheScoreNull() {
        Course course = fixtures.course(LEARNER);
        attempt(LEARNER, quiz(course, "FINAL_EXAM"), 2);
        Mockito.clearInvocations(directory);

        JsonNode page = api.ok(INSTRUCTOR, get("/api/v1/instructor/courses?size=100&sort=id,desc"));

        JsonNode card = null;
        for (JsonNode row : page.path("items")) {
            card = row.path("courseId").asLong() == course.id() ? row : card;
        }
        assertThat(card).isNotNull();
        assertThat(card.has("bestScorePercent")).isTrue();
        assertThat(card.path("bestScorePercent").isNull()).isTrue();
        verify(directory, never()).scores(any(), any());
    }

    @Test
    void theCsvEndsWithTheBestScoreColumn() throws Exception {
        Course scored = fixtures.course(LEARNER);
        attempt(LEARNER, quiz(scored, "FINAL_EXAM"), 2);
        Course unscored = fixtures.course(LEARNER);

        String csv = api.raw(LEARNER, get(LEARNING + "/progress/export")).getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv.lines().findFirst().orElseThrow()).isEqualTo("﻿Mã khóa,Tên khóa,Lần học,Trạng thái,"
            + "Tiến độ,Bắt đầu,Truy cập gần nhất,Hoàn thành,Hạn,Trạng thái hạn,Điểm cao nhất");
        String scoredRow = row(csv, card(LEARNER, "/courses", scored).path("code").asText());
        assertThat(new BigDecimal(lastCell(scoredRow))).isEqualByComparingTo("100");
        String unscoredRow = row(csv, card(LEARNER, "/courses", unscored).path("code").asText());
        assertThat(lastCell(unscoredRow)).isEmpty();
        assertThat(unscoredRow.split(",", -1)).hasSameSizeAs(csv.lines().findFirst().orElseThrow().split(",", -1));
    }

    private static String row(String csv, String code) {
        return csv.lines().filter(line -> line.startsWith("\"" + code + "\"")).findFirst().orElseThrow();
    }

    private static String lastCell(String row) {
        String last = row.substring(row.lastIndexOf(',') + 1);
        return last.replace("\"", "");
    }

    private long quiz(Course course, String type) {
        List<Long> questions = List.of(fixtures.question(course.id(), competency, "SINGLE", 0),
            fixtures.question(course.id(), competency, "SINGLE", 0));
        Map<String, Object> body = quizBody(course.id());
        body.put("quizType", type);
        body.put("maxAttempts", 5);
        body.put("allowRetakeAfterPass", true);
        long quizId = fixtures.quiz(body, questions, true);
        if ("FINAL_EXAM".equals(type)) {
            jdbc.update("update learning_hub.course_completion_rules set final_quiz_id = ? where revision_id = ?",
                quizId, course.revisionId());
        }
        return quizId;
    }

    /** Trả lời đúng {@code right} câu đầu của đề hai câu, bỏ trống phần còn lại, rồi nộp. */
    private void attempt(String user, long quizId, int right) {
        JsonNode session = api.ok(user, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        for (int i = 0; i < right; i++) {
            JsonNode question = session.path("questions").get(i);
            api.ok(user, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(0).path("id")
                    .asLong())), "/api/v1/me/quiz-attempts/{a}/questions/{q}", attemptId,
                question.path("attemptQuestionId").asLong()));
        }
        api.ok(user, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attemptId));
    }

    private JsonNode card(String user, String list, Course course) {
        JsonNode page = api.ok(user, get(LEARNING + list + "?size=100&sort=id,desc"));
        for (JsonNode row : page.path("items")) {
            if (row.path("courseId").asLong() == course.id()) {
                return row;
            }
        }
        throw new AssertionError("Không có khóa " + course.id() + " trong " + list);
    }
}
