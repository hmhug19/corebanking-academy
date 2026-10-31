package vn.com.coreacademy.assessment;

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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.questionBody;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Mã câu hỏi (questions.code) trong đề đang thi: có khi câu có mã, null khi không, cả lúc bắt đầu và lúc tải lại. */
class AttemptQuestionCodeIT extends AssessmentIntegrationTest {
    private static final String ME = "/api/v1/me";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
    }

    @Test
    void sessionQuestionCarriesTheBankCodeOrNull() {
        Course course = fixtures.course(LEARNER);
        long competency = fixtures.competency();
        String code = AssessmentFixtures.unique("CODE");
        Map<String, Object> coded = questionBody(course.id(), competency, "SINGLE", 0);
        coded.put("code", code);
        coded.put("content", "Câu có mã");
        long codedId = api.ok(INSTRUCTOR, api.post(coded, "/api/v1/questions")).path("id").asLong();
        long plainId = api.ok(INSTRUCTOR, api.post(questionBody(course.id(), competency, "MULTIPLE", 0, 2),
            "/api/v1/questions")).path("id").asLong();
        // API sinh Q<ID> khi để trống mã; câu không mã chỉ có thể tồn tại khi cột bị xóa trực tiếp.
        jdbc.update("UPDATE learning_hub.questions SET code = NULL WHERE question_id = ?", plainId);
        long quizId = fixtures.quiz(quizBody(course.id()), List.of(codedId, plainId), true);

        JsonNode started = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        assertCodes(started, code);

        long attemptId = started.path("attemptId").asLong();
        assertCodes(api.ok(LEARNER, get(ME + "/quiz-attempts/{id}", attemptId)), code);
    }

    private static void assertCodes(JsonNode session, String code) {
        JsonNode withCode = null;
        JsonNode without = null;
        for (JsonNode question : session.path("questions")) {
            if ("Câu có mã".equals(question.path("content").asText())) {
                withCode = question;
            } else {
                without = question;
            }
        }
        assertThat(withCode).isNotNull();
        assertThat(withCode.path("code").asText()).isEqualTo(code);
        assertThat(without).isNotNull();
        assertThat(without.has("code")).as("trường code luôn có mặt").isTrue();
        assertThat(without.path("code").isNull()).isTrue();
    }
}
