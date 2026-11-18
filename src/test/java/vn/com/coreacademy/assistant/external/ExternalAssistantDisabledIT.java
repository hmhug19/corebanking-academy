package vn.com.coreacademy.assistant.external;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;

/** With {@code app.assistant.external.enabled=false} (the default) the feature does not exist for anyone. */
@TestPropertySource(properties = "app.assistant.external.enabled=false")
class ExternalAssistantDisabledIT extends AssessmentIntegrationTest {
    private static final String CONNECTIONS = ExternalConnectionIT.CONNECTIONS;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    private ApiClient api;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    @Test
    void theConnectionEndpointsAnswer404() {
        api.http(INSTRUCTOR, get(CONNECTIONS), 404, "CORE-ENDPOINT_NOT_FOUND");
        api.http(INSTRUCTOR, api.post(Map.of("name", "Laptop", "validDays", 30), CONNECTIONS), 404,
            "CORE-ENDPOINT_NOT_FOUND");
        api.http(INSTRUCTOR, delete(CONNECTIONS + "/{id}", 1), 404, "CORE-ENDPOINT_NOT_FOUND");
    }

    @Test
    void theStatusSaysTheFeatureIsOff() {
        JsonNode status = api.ok(INSTRUCTOR, get(CONNECTIONS + "/status"));
        assertThat(status.path("enabled").asBoolean()).isFalse();
        assertThat(status.path("allowed").asBoolean()).isFalse();
    }
}
