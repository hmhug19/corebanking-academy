package vn.com.coreacademy.assistant.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;

/** Tokens, refresh rotation, revocation and the MCP filter (ticket 03 of tro-ly-ngoai-oauth). */
class OAuthTokenIT extends ExternalAssistantIT {
    private static final String CONNECTIONS = ExternalConnectionIT.CONNECTIONS;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    private OAuthFlow flow;
    private McpClient mcp;
    private String client;

    @BeforeAll
    void setUp() {
        flow = new OAuthFlow(mvc, json);
        mcp = new McpClient(mvc, json);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM learning_hub.external_assistant_connection");
        jdbc.update("DELETE FROM learning_hub.oauth_authorization_code");
        jdbc.update("DELETE FROM learning_hub.oauth_authorization_request");
        jdbc.update("DELETE FROM learning_hub.oauth_client");
        jdbc.update("DELETE FROM learning_hub.audit_log WHERE action = 'EXTERNAL_ASSISTANT_OAUTH'");
        client = flow.register("Claude Code", "http://127.0.0.1/callback");
    }

    @Test
    void registerAuthorizeApproveAndExchangeGiveATokenTheMcpEndpointAccepts() {
        JsonNode tokens = exchange(flow.code(INSTRUCTOR, client));

        assertThat(tokens.path("token_type").asText()).isEqualTo("Bearer");
        assertThat(tokens.path("expires_in").asInt()).isEqualTo(3600);
        assertThat(tokens.path("scope").asText()).isEqualTo("course_drafting");
        String access = tokens.path("access_token").asText();
        assertThat(access).startsWith("chan_oat_");
        assertThat(tokens.path("refresh_token").asText()).startsWith("chan_ort_");

        mcp.initialize(access);
        JsonNode tools = mcp.result(access, "tools/list", Map.of());
        assertThat(tools.path("tools")).hasSize(5);

        // The connection is now listed, lives 30 days, and holds only hashes.
        Map<String, Object> row = jdbc.queryForMap("SELECT access_token_hash, refresh_token_hash, "
            + "expires_at > now() + interval '29 days' AS thirty_days, last_used_at IS NOT NULL AS used "
            + "FROM learning_hub.external_assistant_connection");
        assertThat(row).containsEntry("access_token_hash", ExternalConnectionService.hash(access))
            .containsEntry("refresh_token_hash", ExternalConnectionService.hash(tokens.path("refresh_token").asText()))
            .containsEntry("thirty_days", true).containsEntry("used", true);
        JsonNode listed = new ApiClient(mvc, json).ok(INSTRUCTOR, ApiClient.get(CONNECTIONS));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("kind").asText()).isEqualTo("oauth");
        assertThat(listed.get(0).path("tokenPrefix").isNull()).isTrue();
    }

    @Test
    void aWrongVerifierRedirectOrClientAndAnExpiredCodeAreRefused() {
        assertGrantRefused(form(flow.code(INSTRUCTOR, client), "x".repeat(43), OAuthFlow.CALLBACK, client),
            "invalid_grant");
        assertGrantRefused(form(flow.code(INSTRUCTOR, client), OAuthFlow.VERIFIER, "http://127.0.0.1:1/other",
            client), "invalid_grant");
        String other = flow.register("Other", "http://127.0.0.1/callback");
        assertGrantRefused(form(flow.code(INSTRUCTOR, client), OAuthFlow.VERIFIER, OAuthFlow.CALLBACK, other),
            "invalid_grant");

        String code = flow.code(INSTRUCTOR, client);
        jdbc.update("UPDATE learning_hub.oauth_authorization_code SET expires_at = now() - interval '1 second'");
        assertGrantRefused(form(code, OAuthFlow.VERIFIER, OAuthFlow.CALLBACK, client), "invalid_grant");

        MockHttpServletResponse unknown = flow.token(form(code, OAuthFlow.VERIFIER, OAuthFlow.CALLBACK,
            "chan_cli_unknown"));
        assertThat(unknown.getStatus()).isEqualTo(401);
        assertThat(flow.body(unknown).path("error").asText()).isEqualTo("invalid_client");
        assertGrantRefused(Map.of("grant_type", "password", "client_id", client), "unsupported_grant_type");
    }

    @Test
    void aCodeUsedTwiceRevokesWhatItGave() {
        String code = flow.code(INSTRUCTOR, client);
        String access = exchange(code).path("access_token").asText();

        assertGrantRefused(form(code, OAuthFlow.VERIFIER, OAuthFlow.CALLBACK, client), "invalid_grant");

        assertThat(mcp.raw("Bearer " + access, "tools/list", Map.of()).getStatus()).isEqualTo(401);
    }

    @Test
    void aRefreshRotatesThePairAndReusingTheOldRefreshTokenRevokesTheConnection() {
        JsonNode first = exchange(flow.code(INSTRUCTOR, client));
        String oldRefresh = first.path("refresh_token").asText();

        JsonNode second = refresh(oldRefresh, 200);
        String access = second.path("access_token").asText();
        assertThat(access).isNotEqualTo(first.path("access_token").asText());
        assertThat(second.path("refresh_token").asText()).isNotEqualTo(oldRefresh);
        // The old access token stops at once; the new one works.
        assertThat(mcp.raw("Bearer " + first.path("access_token").asText(), "tools/list", Map.of()).getStatus())
            .isEqualTo(401);
        mcp.initialize(access);

        assertThat(refresh(oldRefresh, 400).path("error").asText()).isEqualTo("invalid_grant");

        assertThat(mcp.raw("Bearer " + access, "tools/list", Map.of()).getStatus()).isEqualTo(401);
        assertThat(refresh(second.path("refresh_token").asText(), 400).path("error").asText())
            .isEqualTo("invalid_grant");
        assertThat(events()).contains("REFRESH_REUSED");
    }

    @Test
    void anAccessTokenStopsAfterAnHourAndAConnectionLapsesThirtyDaysAfterItsLastRefresh() {
        JsonNode tokens = exchange(flow.code(INSTRUCTOR, client));
        String access = tokens.path("access_token").asText();

        jdbc.update("UPDATE learning_hub.external_assistant_connection SET access_expires_at = now() - interval '1 s'");
        assertThat(mcp.raw("Bearer " + access, "tools/list", Map.of()).getStatus()).isEqualTo(401);
        JsonNode renewed = refresh(tokens.path("refresh_token").asText(), 200);
        mcp.initialize(renewed.path("access_token").asText());

        jdbc.update("UPDATE learning_hub.external_assistant_connection SET expires_at = now() - interval '1 second'");
        assertThat(mcp.raw("Bearer " + renewed.path("access_token").asText(), "tools/list", Map.of()).getStatus())
            .isEqualTo(401);
        assertThat(refresh(renewed.path("refresh_token").asText(), 400).path("error").asText())
            .isEqualTo("invalid_grant");
        assertThat(events()).contains("REFRESH_REFUSED");
    }

    @Test
    void revokingFromTheDrawerOrByRfc7009StopsBothTokensAtOnce() {
        JsonNode fromDrawer = exchange(flow.code(INSTRUCTOR, client));
        long id = jdbc.queryForObject("SELECT connection_id FROM learning_hub.external_assistant_connection",
            Long.class);
        new ApiClient(mvc, json).ok(INSTRUCTOR, ApiClient.delete(CONNECTIONS + "/{id}", id));
        assertThat(mcp.raw("Bearer " + fromDrawer.path("access_token").asText(), "tools/list", Map.of()).getStatus())
            .isEqualTo(401);
        assertThat(refresh(fromDrawer.path("refresh_token").asText(), 400).path("error").asText())
            .isEqualTo("invalid_grant");

        JsonNode byClient = exchange(flow.code(INSTRUCTOR, client));
        MockHttpServletResponse revoked = flow.form("/api/v1/oauth/revoke",
            Map.of("token", byClient.path("refresh_token").asText(), "client_id", client));
        assertThat(revoked.getStatus()).isEqualTo(200);
        assertThat(mcp.raw("Bearer " + byClient.path("access_token").asText(), "tools/list", Map.of()).getStatus())
            .isEqualTo(401);
        // An unknown token answers the same.
        assertThat(flow.form("/api/v1/oauth/revoke", Map.of("token", "chan_ort_nothing")).getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForList("SELECT details ->> 'reason' FROM learning_hub.audit_log WHERE action = "
            + "'EXTERNAL_ASSISTANT_OAUTH' AND details ->> 'event' = 'REVOKED' ORDER BY audit_id", String.class))
            .containsExactly("user", "rfc7009");
    }

    @Test
    void anOAuthTokenWorksOnlyOnTheMcpEndpoint() throws Exception {
        String access = exchange(flow.code(INSTRUCTOR, client)).path("access_token").asText();

        for (String path : List.of(CONNECTIONS, "/api/v1/auth/me", "/api/v1/courses")) {
            MockHttpServletResponse response = mvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access)).andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isEqualTo(401);
        }
    }

    @Test
    void aConnectionWhoseUserLostTheRoleStops() {
        String access = exchange(flow.code(INSTRUCTOR, client)).path("access_token").asText();
        jdbc.update("UPDATE learning_hub.external_assistant_connection SET user_id = '000004'");

        assertThat(mcp.raw("Bearer " + access, "tools/list", Map.of()).getStatus()).isEqualTo(401);
    }

    private JsonNode exchange(String code) {
        MockHttpServletResponse response = flow.token(form(code, OAuthFlow.VERIFIER, OAuthFlow.CALLBACK, client));
        assertThat(response.getStatus()).as(contentOf(response)).isEqualTo(200);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        return flow.body(response);
    }

    private JsonNode refresh(String refreshToken, int status) {
        MockHttpServletResponse response = flow.token(Map.of("grant_type", "refresh_token", "refresh_token",
            refreshToken, "client_id", client));
        assertThat(response.getStatus()).as(contentOf(response)).isEqualTo(status);
        return flow.body(response);
    }

    private void assertGrantRefused(Map<String, String> form, String error) {
        MockHttpServletResponse response = flow.token(form);
        assertThat(response.getStatus()).as(contentOf(response)).isEqualTo(400);
        assertThat(flow.body(response).path("error").asText()).isEqualTo(error);
    }

    private static Map<String, String> form(String code, String verifier, String redirect, String clientId) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("code_verifier", verifier);
        form.put("redirect_uri", redirect);
        form.put("client_id", clientId);
        return form;
    }

    private List<String> events() {
        return jdbc.queryForList("SELECT details ->> 'event' FROM learning_hub.audit_log WHERE action = "
            + "'EXTERNAL_ASSISTANT_OAUTH'", String.class);
    }

    private static String contentOf(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (Exception exception) {
            return "";
        }
    }
}
