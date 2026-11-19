package vn.com.coreacademy.assistant.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;

/** Authorize and the Cho phép kết nối API (ticket 02 of tro-ly-ngoai-oauth). */
class OAuthAuthorizeIT extends ExternalAssistantIT {
    private static final String REQUESTS = "/api/v1/assistant/oauth-requests/{id}";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    private OAuthFlow flow;
    private String client;

    @BeforeAll
    void setUp() {
        flow = new OAuthFlow(mvc, json);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM learning_hub.external_assistant_connection");
        jdbc.update("DELETE FROM learning_hub.oauth_authorization_code");
        jdbc.update("DELETE FROM learning_hub.oauth_authorization_request");
        jdbc.update("DELETE FROM learning_hub.oauth_client");
        jdbc.update("DELETE FROM learning_hub.audit_log WHERE action = 'EXTERNAL_ASSISTANT_OAUTH'");
        client = flow.register("Claude Code (chan-academy)", "http://127.0.0.1/callback",
            "https://claude.ai/api/mcp/auth_callback");
    }

    @Test
    void aGoodRequestGoesToTheChoPhepPageWhichShowsTheSelfDeclaredName() {
        String request = flow.requestId(client, OAuthFlow.CALLBACK);

        JsonNode shown = flow.read(INSTRUCTOR, request);
        assertThat(shown.path("clientName").asText()).isEqualTo("Claude Code (chan-academy)");
        assertThat(shown.path("redirectHost").asText()).isEqualTo("127.0.0.1");
        assertThat(shown.path("idleDays").asInt()).isEqualTo(30);
        assertThat(shown.path("atLimit").asBoolean()).isFalse();
        assertThat(shown.path("maxConnections").asInt()).isEqualTo(10);
    }

    @Test
    void anUnknownClientOrAWrongRedirectShowsAnErrorAndNeverRedirects() {
        for (Map<String, String> params : new Map[] {OAuthFlow.params("chan_cli_unknown", OAuthFlow.CALLBACK),
            OAuthFlow.params(null, OAuthFlow.CALLBACK), OAuthFlow.params(client, "https://evil.example/cb"),
            OAuthFlow.params(client, "http://127.0.0.1:53682/other"), OAuthFlow.params(client, null),
            OAuthFlow.params(client, "https://claude.com/api/mcp/auth_callback")}) {
            MockHttpServletResponse response = flow.authorize(params);
            assertThat(response.getStatus()).as(params.toString()).isEqualTo(400);
            assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
            assertThat(response.getContentType()).startsWith("text/html");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.audit_log WHERE action = "
            + "'EXTERNAL_ASSISTANT_OAUTH' AND details ->> 'event' = 'AUTHORIZE_REFUSED'", Integer.class))
            .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.oauth_authorization_request",
            Integer.class)).isZero();
    }

    @Test
    void otherErrorsGoBackToTheClientWithTheirStateAndIssuer() {
        assertRedirectError(Map.of("response_type", "token"), "unsupported_response_type");
        assertRedirectError(Map.of("code_challenge_method", "plain"), "invalid_request");
        assertRedirectError(Map.of("code_challenge", "short"), "invalid_request");
        assertRedirectError(Map.of("scope", "admin"), "invalid_scope");
        assertRedirectError(Map.of("resource", "https://lms.example/api/v1/other"), "invalid_target");
    }

    @Test
    void approveMakesAConnectionAndAOneTimeCodeSentBackWithState() {
        String request = flow.requestId(client, OAuthFlow.CALLBACK);

        String redirect = flow.approve(INSTRUCTOR, request);

        assertThat(redirect).startsWith(OAuthFlow.CALLBACK + "?");
        Map<String, String> query = OAuthFlow.query(redirect);
        assertThat(query.get("code")).hasSizeGreaterThan(40);
        assertThat(query.get("state")).isEqualTo("state-123");
        assertThat(query.get("iss")).isEqualTo("https://lms.example");
        Map<String, Object> connection = jdbc.queryForMap("SELECT user_id, kind, client_id, name, token_hash, "
            + "expires_at > now() + interval '2 minutes' AS long_lived FROM learning_hub.external_assistant_connection");
        assertThat(connection).containsEntry("user_id", INSTRUCTOR).containsEntry("kind", "oauth")
            .containsEntry("client_id", client).containsEntry("name", "Claude Code (chan-academy)")
            .containsEntry("long_lived", false);
        assertThat(connection.get("token_hash")).isNull();
        // Only the code's hash is kept.
        assertThat(jdbc.queryForObject("SELECT code_hash FROM learning_hub.oauth_authorization_code", String.class))
            .isEqualTo(ExternalConnectionService.hash(query.get("code")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.audit_log WHERE action = "
            + "'EXTERNAL_ASSISTANT_OAUTH' AND details ->> 'event' = 'GRANTED' AND actor_id = ?", Integer.class,
            INSTRUCTOR)).isEqualTo(1);

        // A request works once.
        flow.api().error(INSTRUCTOR, flow.api().post(Map.of(), REQUESTS + "/approve", request),
            "CORE-RESOURCE_NOT_FOUND");
        flow.api().error(INSTRUCTOR, ApiClient.get(REQUESTS, request), "CORE-RESOURCE_NOT_FOUND");
    }

    @Test
    void denyReturnsAccessDeniedAndMakesNothing() {
        String request = flow.requestId(client, OAuthFlow.CALLBACK);

        Map<String, String> query = OAuthFlow.query(flow.deny(ADMIN, request));

        assertThat(query).containsEntry("error", "access_denied").containsEntry("state", "state-123");
        assertThat(query).doesNotContainKey("code");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.external_assistant_connection",
            Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.audit_log WHERE action = "
            + "'EXTERNAL_ASSISTANT_OAUTH' AND details ->> 'event' = 'DENIED'", Integer.class)).isEqualTo(1);
        flow.api().error(ADMIN, flow.api().post(Map.of(), REQUESTS + "/approve", request), "CORE-RESOURCE_NOT_FOUND");
    }

    @Test
    void anExpiredRequestCannotBeAnswered() {
        String request = flow.requestId(client, OAuthFlow.CALLBACK);
        jdbc.update("UPDATE learning_hub.oauth_authorization_request SET expires_at = now() - interval '1 second'");

        flow.api().error(INSTRUCTOR, ApiClient.get(REQUESTS, request), "CORE-RESOURCE_NOT_FOUND");
        flow.api().error(INSTRUCTOR, flow.api().post(Map.of(), REQUESTS + "/approve", request),
            "CORE-RESOURCE_NOT_FOUND");
    }

    @Test
    void aLearnerMayNotAnswer() {
        String request = flow.requestId(client, OAuthFlow.CALLBACK);

        flow.api().http(LEARNER, ApiClient.get(REQUESTS, request), 403, "CORE-ACCESS_DENIED");
        flow.api().http(LEARNER, flow.api().post(Map.of(), REQUESTS + "/approve", request), 403,
            "CORE-ACCESS_DENIED");
        flow.api().http(null, flow.api().post(Map.of(), REQUESTS + "/approve", request), 401,
            "CORE-UNAUTHENTICATED");
    }

    @Test
    void theTenthLiveConnectionIsTheLastAndTheRequestStaysOpen() {
        for (int i = 0; i < 10; i++) {
            jdbc.update("INSERT INTO learning_hub.external_assistant_connection (user_id, name, kind, client_id, "
                + "expires_at) VALUES (?, 'x', 'oauth', ?, now() + interval '1 day')", INSTRUCTOR, client);
        }
        String request = flow.requestId(client, OAuthFlow.CALLBACK);

        assertThat(flow.read(INSTRUCTOR, request).path("atLimit").asBoolean()).isTrue();
        JsonNode refused = flow.api().error(INSTRUCTOR, flow.api().post(Map.of(), REQUESTS + "/approve", request),
            "CORE-STATE_CONFLICT");
        assertThat(refused.path("errors").get(0).path("field").asText()).isEqualTo("connections");

        // Revoking one (here: letting it lapse) makes room, and the same request still works.
        jdbc.update("UPDATE learning_hub.external_assistant_connection SET revoked_at = now() WHERE connection_id = "
            + "(SELECT min(connection_id) FROM learning_hub.external_assistant_connection)");
        assertThat(OAuthFlow.query(flow.approve(INSTRUCTOR, request))).containsKey("code");
    }

    @Test
    void aLoopbackRedirectMayUseAnyPort() {
        String request = flow.requestId(client, "http://127.0.0.1:61000/callback");

        assertThat(flow.approve(INSTRUCTOR, request)).startsWith("http://127.0.0.1:61000/callback?code=");
    }

    private void assertRedirectError(Map<String, String> change, String error) {
        Map<String, String> params = OAuthFlow.params(client, OAuthFlow.CALLBACK);
        params.putAll(change);
        MockHttpServletResponse response = flow.authorize(params);
        assertThat(response.getStatus()).as(change.toString()).isEqualTo(302);
        String location = response.getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith(OAuthFlow.CALLBACK + "?");
        assertThat(OAuthFlow.query(location)).containsEntry("error", error).containsEntry("state", "state-123")
            .containsEntry("iss", "https://lms.example");
    }
}
