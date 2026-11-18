package vn.com.coreacademy.assistant.external;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;

/**
 * With {@code app.assistant.external.enabled=false}, the default (this IT runs on the default context), the feature does
 * not exist for anyone.
 */
class ExternalAssistantDisabledIT extends AssessmentIntegrationTest {
    private static final String CONNECTIONS = ExternalConnectionIT.CONNECTIONS;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

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
    void theMcpEndpointAnswers404EvenWithAValidToken() {
        // A connection made while the feature was on stays in the table but cannot be used.
        String token = ExternalConnectionCodes.TOKEN_PREFIX + "made-while-on";
        jdbc.update("INSERT INTO learning_hub.external_assistant_connection (user_id, name, token_hash, token_prefix, "
            + "expires_at) VALUES (?, 'x', ?, 'chan_ext_made', now() + interval '1 day')", INSTRUCTOR,
            ExternalConnectionService.hash(token));
        try {
            McpClient mcp = new McpClient(mvc, json);
            assertThat(mcp.raw("Bearer " + token, "tools/list", Map.of()).getStatus()).isEqualTo(404);
            assertThat(mcp.raw(null, "tools/list", Map.of()).getStatus()).isEqualTo(404);
        } finally {
            jdbc.update("DELETE FROM learning_hub.external_assistant_connection");
        }
    }

    @Test
    void theStatusSaysTheFeatureIsOff() {
        JsonNode status = api.ok(INSTRUCTOR, get(CONNECTIONS + "/status"));
        assertThat(status.path("enabled").asBoolean()).isFalse();
        assertThat(status.path("allowed").asBoolean()).isFalse();
    }
}
