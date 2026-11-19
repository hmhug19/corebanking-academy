package vn.com.coreacademy.assistant.external;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;

/**
 * With {@code app.assistant.external.enabled=false}, as every IT but an ExternalAssistantIT runs, the feature does
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
    void theOAuthEndpointsAnswer404() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/api/v1/mcp", "/.well-known/oauth-authorization-server",
            "/api/v1/oauth/authorize?client_id=x")) {
            assertThat(mvc.perform(MockMvcRequestBuilders.get(path)).andReturn().getResponse().getStatus())
                .as(path).isEqualTo(404);
        }
        assertThat(mvc.perform(MockMvcRequestBuilders.post("/api/v1/oauth/register")
            .contentType(MediaType.APPLICATION_JSON).content("{\"redirect_uris\":[\"http://localhost:1/cb\"]}"))
            .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void theChoPhepApiAnswers404() {
        api.http(INSTRUCTOR, get("/api/v1/assistant/oauth-requests/{id}", "x"), 404, "CORE-ENDPOINT_NOT_FOUND");
        api.http(INSTRUCTOR, api.post(Map.of(), "/api/v1/assistant/oauth-requests/{id}/approve", "x"), 404,
            "CORE-ENDPOINT_NOT_FOUND");
    }

    @Test
    void theStatusSaysTheFeatureIsOff() {
        JsonNode status = api.ok(INSTRUCTOR, get(CONNECTIONS + "/status"));
        assertThat(status.path("enabled").asBoolean()).isFalse();
        assertThat(status.path("allowed").asBoolean()).isFalse();
    }
}
