package vn.com.coreacademy.assessment;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** FE sinh client từ Swagger: mọi endpoint Quiz Engine phải có trong /v3/api-docs với đúng method. */
class AssessmentOpenApiIT extends AssessmentIntegrationTest {
    private static final Map<String, String> OPERATIONS = Map.ofEntries(
        Map.entry("/api/v1/competencies", "get,post"),
        Map.entry("/api/v1/competencies/{id}", "put,delete"),
        Map.entry("/api/v1/questions", "get,post"),
        Map.entry("/api/v1/questions/{id}", "get,put"),
        Map.entry("/api/v1/questions/bulk-status", "post"),
        Map.entry("/api/v1/questions/bulk-delete", "post"),
        Map.entry("/api/v1/questions/import-template", "get"),
        Map.entry("/api/v1/questions/import", "post"),
        Map.entry("/api/v1/questions/export", "get"),
        Map.entry("/api/v1/quizzes", "get,post"),
        Map.entry("/api/v1/quizzes/{id}", "get,put,delete"),
        Map.entry("/api/v1/quizzes/{id}/questions", "put"),
        Map.entry("/api/v1/quizzes/{id}/publish", "post"),
        Map.entry("/api/v1/quizzes/{id}/close", "post"),
        Map.entry("/api/v1/quizzes/{id}/preview", "get"),
        Map.entry("/api/v1/quizzes/{id}/regrade", "post"),
        Map.entry("/api/v1/reports/assessment/overview", "get"),
        Map.entry("/api/v1/reports/assessment/results", "get"),
        Map.entry("/api/v1/reports/assessment/learners", "get"),
        Map.entry("/api/v1/reports/assessment/competency", "get"),
        Map.entry("/api/v1/reports/assessment/questions", "get"),
        Map.entry("/api/v1/reports/assessment/{type}/export", "get"),
        Map.entry("/api/v1/me/competency", "get"),
        Map.entry("/api/v1/quizzes/{quizId}/attempts", "get"),
        Map.entry("/api/v1/quizzes/{quizId}/grants", "get,post"),
        Map.entry("/api/v1/quizzes/{quizId}/exhausted-learners", "get"),
        Map.entry("/api/v1/quiz-attempts/{attemptId}", "get"),
        Map.entry("/api/v1/quiz-attempts/{attemptId}/void", "post"),
        Map.entry("/api/v1/quiz-attempts/{attemptId}/force-submit", "post"),
        Map.entry("/api/v1/quiz-attempt-grants/{grantId}/revoke", "post"),
        Map.entry("/api/v1/me/quizzes/{quizId}", "get"),
        Map.entry("/api/v1/me/quizzes/{quizId}/attempts", "get,post"),
        Map.entry("/api/v1/me/quiz-attempts", "get"),
        Map.entry("/api/v1/me/quiz-attempts/export", "get"),
        Map.entry("/api/v1/me/quiz-attempts/{attemptId}", "get"),
        Map.entry("/api/v1/me/quiz-attempts/{attemptId}/questions/{attemptQuestionId}", "patch"),
        Map.entry("/api/v1/me/quiz-attempts/{attemptId}/submit", "post"),
        Map.entry("/api/v1/me/quiz-attempts/{attemptId}/result", "get"),
        Map.entry("/api/v1/me/courses/{courseId}/quizzes", "get"));

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void everyAssessmentOperationIsDocumented() throws Exception {
        String content = mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
        JsonNode paths = json.readTree(content).path("paths");
        int operations = 0;
        for (Map.Entry<String, String> entry : OPERATIONS.entrySet()) {
            JsonNode path = paths.path(entry.getKey());
            assertThat(path.isMissingNode()).as("thiếu " + entry.getKey()).isFalse();
            for (String method : entry.getValue().split(",")) {
                JsonNode operation = path.path(method);
                assertThat(operation.isMissingNode()).as(method + " " + entry.getKey()).isFalse();
                assertThat(operation.path("summary").asText()).as("summary " + entry.getKey()).isNotBlank();
                operations++;
            }
        }
        assertThat(operations).isEqualTo(48);
    }
}
