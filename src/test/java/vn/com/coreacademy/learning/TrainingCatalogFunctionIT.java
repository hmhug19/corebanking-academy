package vn.com.coreacademy.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ROOT;

/** Menu "Lĩnh vực và chương trình": chức năng TRAINING_CATALOG có cho quản trị và giảng viên, không có cho học viên. */
class TrainingCatalogFunctionIT extends AssessmentIntegrationTest {
    private static final String FUNCTION = "TRAINING_CATALOG";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private ApiClient api;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    private JsonNode functions(String user) {
        return api.ok(user, ApiClient.get("/api/v1/auth/me")).path("functionDetails");
    }

    @Test
    void adminAndInstructorGetTheScreenAndTheLearnerDoesNot() {
        for (String user : new String[] {ROOT, ADMIN, INSTRUCTOR}) {
            assertThat(functions(user).findValuesAsText("code")).as(user).contains(FUNCTION);
        }
        assertThat(functions(LEARNER).findValuesAsText("code")).doesNotContain(FUNCTION);
    }

    @Test
    void theEntryPointsAtTheCatalogRouteRightBeforeCourseManagement() {
        JsonNode entry = null;
        for (JsonNode function : functions(ADMIN)) {
            if (FUNCTION.equals(function.path("code").asText())) {
                entry = function;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.path("routePattern").asText()).isEqualTo("/admin/catalog");
        assertThat(entry.path("name").asText()).isEqualTo("Lĩnh vực và chương trình");
        assertThat(entry.path("sortNo").asInt()).isLessThan(50).isGreaterThan(40);
    }
}
