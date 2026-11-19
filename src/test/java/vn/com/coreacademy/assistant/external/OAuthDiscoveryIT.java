package vn.com.coreacademy.assistant.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * OAuth discovery and client registration (ticket 01 of tro-ly-ngoai-oauth): a client that has only the MCP URL finds
 * the metadata from a 401, then registers itself with an allowed redirect URI.
 */
class OAuthDiscoveryIT extends ExternalAssistantIT {
    static final String REGISTER = "/api/v1/oauth/register";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM learning_hub.oauth_client");
    }

    @Test
    void aRefusedMcpCallNamesTheResourceMetadata() throws Exception {
        MockHttpServletResponse response = new McpClient(mvc, json).raw(null, "tools/list", Map.of());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(
            "Bearer resource_metadata=\"https://lms.example/.well-known/oauth-protected-resource/api/v1/mcp\"");
    }

    @Test
    void theProtectedResourceMetadataNamesTheMcpUrlAndTheIssuer() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource",
            "/.well-known/oauth-protected-resource/api/v1/mcp")) {
            JsonNode body = okJson(path);
            assertThat(body.path("resource").asText()).isEqualTo("https://lms.example/api/v1/mcp");
            assertThat(body.path("authorization_servers").get(0).asText()).isEqualTo("https://lms.example");
            assertThat(body.path("scopes_supported").get(0).asText()).isEqualTo("course_drafting");
        }
    }

    @Test
    void theAuthorizationServerMetadataOffersCodeWithPkceAndRegistration() throws Exception {
        JsonNode body = okJson("/.well-known/oauth-authorization-server");

        assertThat(body.path("issuer").asText()).isEqualTo("https://lms.example");
        assertThat(body.path("authorization_endpoint").asText()).isEqualTo("https://lms.example/api/v1/oauth/authorize");
        assertThat(body.path("token_endpoint").asText()).isEqualTo("https://lms.example/api/v1/oauth/token");
        assertThat(body.path("registration_endpoint").asText()).isEqualTo("https://lms.example/api/v1/oauth/register");
        assertThat(body.path("revocation_endpoint").asText()).isEqualTo("https://lms.example/api/v1/oauth/revoke");
        assertThat(body.path("code_challenge_methods_supported").toString()).isEqualTo("[\"S256\"]");
        assertThat(body.path("token_endpoint_auth_methods_supported").toString()).isEqualTo("[\"none\"]");
        assertThat(body.path("grant_types_supported").toString())
            .isEqualTo("[\"authorization_code\",\"refresh_token\"]");
    }

    @Test
    void claudeAppAndClaudeCodeRegister() throws Exception {
        JsonNode app = register(Map.of("client_name", "Claude", "redirect_uris",
            List.of("https://claude.ai/api/mcp/auth_callback", "https://claude.com/api/mcp/auth_callback"),
            "token_endpoint_auth_method", "none", "grant_types", List.of("authorization_code", "refresh_token"),
            "response_types", List.of("code"), "logo_uri", "https://claude.ai/logo.png"), 201);
        JsonNode code = register(Map.of("client_name", "Claude Code (chan-academy)",
            "redirect_uris", List.of("http://localhost:53682/callback", "http://127.0.0.1:53682/callback")), 201);

        assertThat(app.path("client_id").asText()).startsWith("chan_cli_");
        assertThat(app.path("client_name").asText()).isEqualTo("Claude");
        assertThat(app.path("token_endpoint_auth_method").asText()).isEqualTo("none");
        assertThat(app.has("client_secret")).isFalse();
        assertThat(code.path("redirect_uris")).hasSize(2);
        assertThat(code.path("client_id").asText()).isNotEqualTo(app.path("client_id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.oauth_client", Integer.class)).isEqualTo(2);
    }

    @Test
    void aClientWithoutANameIsShownAsTroLyNgoai() throws Exception {
        JsonNode body = register(Map.of("redirect_uris", List.of("http://127.0.0.1:9000/cb")), 201);

        assertThat(body.path("client_name").asText()).isEqualTo("Trợ lý ngoài");
    }

    @Test
    void aRedirectUriThatIsNeitherClaudeNorLoopbackIsRefusedAndAudited() throws Exception {
        jdbc.update("DELETE FROM learning_hub.audit_log WHERE action = 'EXTERNAL_ASSISTANT_OAUTH'");
        for (String uri : List.of("https://evil.example/callback", "http://evil.example:8080/cb",
            "https://claude.ai/other", "https://localhost/cb", "http://user@localhost:1/cb", "javascript:alert(1)")) {
            JsonNode body = register(Map.of("client_name", "x", "redirect_uris", List.of(uri)), 400);
            assertThat(body.path("error").asText()).as(uri).isEqualTo("invalid_redirect_uri");
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.oauth_client", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.audit_log WHERE action = "
            + "'EXTERNAL_ASSISTANT_OAUTH' AND details ->> 'event' = 'REGISTRATION_REFUSED'", Integer.class))
            .isEqualTo(6);
    }

    @Test
    void confidentialClientsAndOtherGrantsAreRefused() throws Exception {
        List<String> uris = List.of("http://localhost:1/cb");
        assertThat(register(Map.of("redirect_uris", uris, "token_endpoint_auth_method", "client_secret_basic"), 400)
            .path("error").asText()).isEqualTo("invalid_client_metadata");
        assertThat(register(Map.of("redirect_uris", uris, "grant_types", List.of("client_credentials")), 400)
            .path("error").asText()).isEqualTo("invalid_client_metadata");
        assertThat(register(Map.of("redirect_uris", List.of()), 400).path("error").asText())
            .isEqualTo("invalid_redirect_uri");
    }

    private JsonNode okJson(String path) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).as(path).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }

    private JsonNode register(Map<String, Object> body, int status) throws Exception {
        MockHttpServletResponse response = mvc.perform(post(REGISTER).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }
}
