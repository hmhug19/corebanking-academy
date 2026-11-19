package vn.com.coreacademy.assistant.external;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.Cookie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assistant.external.oauth.OAuthCleanupJob;

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

    @Autowired
    private OAuthCleanupJob cleanup;

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
    void authorizeBindsTheRequestToTheBrowserWithAnHttpOnlyCookieOnlyTheConsentApiGets() {
        MockHttpServletResponse response = flow.authorize(OAuthFlow.params(client, OAuthFlow.CALLBACK));

        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(header).startsWith("chan_oauth_request=").contains("Path=/api/v1/assistant/oauth-requests")
            .contains("Max-Age=600").contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        String cookie = response.getCookie("chan_oauth_request").getValue();
        assertThat(jdbc.queryForObject("SELECT browser_hash FROM learning_hub.oauth_authorization_request",
            String.class)).isEqualTo(ExternalConnectionService.hash(cookie));
        // An error sent back to the client starts nothing, so it binds nothing.
        Map<String, String> wrong = OAuthFlow.params(client, OAuthFlow.CALLBACK);
        wrong.put("response_type", "token");
        assertThat(flow.authorize(wrong).getHeader(HttpHeaders.SET_COOKIE)).isNull();
    }

    @Test
    void aRequestLinkOpenedInAnotherBrowserCannotBeReadOrAnsweredAndIsAudited() {
        String request = flow.requestId(client, OAuthFlow.CALLBACK);
        Cookie other = new Cookie("chan_oauth_request", "someone-elses-browser");

        for (MockHttpServletRequestBuilder call : List.of(ApiClient.get(REQUESTS, request),
            ApiClient.get(REQUESTS, request).cookie(other),
            flow.api().post(Map.of(), REQUESTS + "/approve", request),
            flow.api().post(Map.of(), REQUESTS + "/approve", request).cookie(other),
            flow.api().post(Map.of(), REQUESTS + "/deny", request).cookie(other))) {
            flow.api().error(INSTRUCTOR, call, "CORE-RESOURCE_NOT_FOUND");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.audit_log WHERE action = "
            + "'EXTERNAL_ASSISTANT_OAUTH' AND details ->> 'event' = 'ANSWER_REFUSED' AND actor_id = ?", Integer.class,
            INSTRUCTOR)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.external_assistant_connection",
            Integer.class)).isZero();

        // The browser that started it still can.
        assertThat(OAuthFlow.query(flow.approve(INSTRUCTOR, request))).containsKey("code");
    }

    @Test
    void theCleanupRemovesWhatNothingCanUseAndKeepsEveryConnectionThatWorked() {
        jdbc.update("INSERT INTO learning_hub.oauth_client (client_id, client_name, redirect_uris, create_time) "
            + "VALUES ('chan_cli_old', 'x', '[]', now() - interval '8 days'), "
            + "('chan_cli_new', 'x', '[]', now() - interval '1 day'), "
            + "('chan_cli_used', 'x', '[]', now() - interval '30 days')");
        jdbc.update("INSERT INTO learning_hub.oauth_authorization_request (request_id, client_id, redirect_uri, "
            + "code_challenge, scope, browser_hash, expires_at) VALUES "
            + "('old', 'chan_cli_used', 'x', 'c', 's', repeat('a', 64), now() - interval '2 days'), "
            + "('open', 'chan_cli_used', 'x', 'c', 's', repeat('a', 64), now() + interval '5 minutes')");
        jdbc.update("INSERT INTO learning_hub.external_assistant_connection (user_id, name, kind, "
            + "client_id, access_token_hash, expires_at) VALUES "
            + "(?, 'never exchanged', 'oauth', 'chan_cli_used', NULL, now() - interval '2 days'), "
            + "(?, 'worked, lapsed', 'oauth', 'chan_cli_used', repeat('b', 64), now() - interval '40 days'), "
            + "(?, 'just approved', 'oauth', 'chan_cli_used', NULL, now() + interval '1 minute')",
            INSTRUCTOR, INSTRUCTOR, INSTRUCTOR);
        jdbc.update("INSERT INTO learning_hub.oauth_authorization_code (code_hash, connection_id, redirect_uri, "
            + "code_challenge, expires_at) SELECT repeat('c', 64), connection_id, 'x', 'c', now() - interval '2 days' "
            + "FROM learning_hub.external_assistant_connection WHERE name = 'never exchanged'");

        cleanup.purge();

        assertThat(jdbc.queryForList("SELECT client_id FROM learning_hub.oauth_client ORDER BY 1", String.class))
            .contains("chan_cli_new", "chan_cli_used").doesNotContain("chan_cli_old");
        assertThat(jdbc.queryForList("SELECT request_id FROM learning_hub.oauth_authorization_request",
            String.class)).containsExactly("open");
        assertThat(jdbc.queryForList("SELECT name FROM learning_hub.external_assistant_connection ORDER BY name",
            String.class)).containsExactly("just approved", "worked, lapsed");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.oauth_authorization_code",
            Integer.class)).isZero();
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
        flow.api().error(INSTRUCTOR, flow.answering(request, "approve"),
            "CORE-RESOURCE_NOT_FOUND");
        flow.api().error(INSTRUCTOR, flow.reading(request), "CORE-RESOURCE_NOT_FOUND");
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
        flow.api().error(ADMIN, flow.answering(request, "approve"), "CORE-RESOURCE_NOT_FOUND");
    }

    @Test
    void anExpiredRequestCannotBeAnswered() {
        String request = flow.requestId(client, OAuthFlow.CALLBACK);
        jdbc.update("UPDATE learning_hub.oauth_authorization_request SET expires_at = now() - interval '1 second'");

        flow.api().error(INSTRUCTOR, flow.reading(request), "CORE-RESOURCE_NOT_FOUND");
        flow.api().error(INSTRUCTOR, flow.answering(request, "approve"),
            "CORE-RESOURCE_NOT_FOUND");
    }

    @Test
    void aLearnerMayNotAnswer() {
        String request = flow.requestId(client, OAuthFlow.CALLBACK);

        flow.api().http(LEARNER, flow.reading(request), 403, "CORE-ACCESS_DENIED");
        flow.api().http(LEARNER, flow.answering(request, "approve"), 403,
            "CORE-ACCESS_DENIED");
        flow.api().http(null, flow.answering(request, "approve"), 401,
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
        JsonNode refused = flow.api().error(INSTRUCTOR, flow.answering(request, "approve"),
            "CORE-STATE_CONFLICT");
        assertThat(refused.path("errors").get(0).path("field").asText()).isEqualTo("connections");

        // Revoking one (here: letting it lapse) makes room, and the same request still works.
        jdbc.update("UPDATE learning_hub.external_assistant_connection SET revoked_at = now() WHERE connection_id = "
            + "(SELECT min(connection_id) FROM learning_hub.external_assistant_connection)");
        assertThat(OAuthFlow.query(flow.approve(INSTRUCTOR, request))).containsKey("code");
    }

    @Test
    void twoApprovalsAtOnceCannotBothTakeTheLastPlace() throws Exception {
        for (int i = 0; i < 9; i++) {
            jdbc.update("INSERT INTO learning_hub.external_assistant_connection (user_id, name, kind, client_id, "
                + "expires_at) VALUES (?, 'x', 'oauth', ?, now() + interval '1 day')", INSTRUCTOR, client);
        }
        List<String> requests = List.of(flow.requestId(client, OAuthFlow.CALLBACK),
            flow.requestId(client, OAuthFlow.CALLBACK));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (String request : requests) {
                answers.add(pool.submit(() -> {
                    start.await();
                    return flow.api().raw(INSTRUCTOR, flow.answering(request, "approve")).getContentAsString();
                }));
            }
            start.countDown();
            List<String> bodies = new ArrayList<>();
            for (Future<String> answer : answers) {
                bodies.add(answer.get(30, TimeUnit.SECONDS));
            }
            assertThat(bodies).filteredOn(body -> body.contains("\"redirectUrl\"")).hasSize(1);
            assertThat(bodies).filteredOn(body -> body.contains("CORE-STATE_CONFLICT")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.external_assistant_connection "
            + "WHERE revoked_at IS NULL AND expires_at > now()", Integer.class)).isEqualTo(10);
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
