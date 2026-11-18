package vn.com.coreacademy.assistant.external;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.service.TokenService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.REPORT_VIEWER;

/**
 * The MCP endpoint of the Trợ lý ngoài, over HTTP with real connection tokens: who gets in, which tools there are,
 * and the start / read / stop tools on drafting sessions.
 */
class ExternalMcpIT extends ExternalAssistantIT {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TokenService tokens;
    @Autowired
    private UserRepository users;

    private ApiClient api;
    private McpClient mcp;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        mcp = new McpClient(mvc, json);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM learning_hub.external_assistant_connection");
        jdbc.update("DELETE FROM learning_hub.course_drafting_steps");
        jdbc.update("DELETE FROM learning_hub.course_drafting_sessions");
    }

    /** A connection made through the page, as its user would. */
    private String connect(String user) {
        return api.ok(user, api.post(Map.of("name", "Claude Code", "validDays", 30),
            ExternalConnectionIT.CONNECTIONS)).path("token").asText();
    }

    /** A connection written straight into the table, for users the page would refuse. */
    private String connectDirectly(String user, OffsetDateTime expiresAt) {
        String token = ExternalConnectionCodes.TOKEN_PREFIX + "direct" + user + System.nanoTime();
        jdbc.update("INSERT INTO learning_hub.external_assistant_connection (user_id, name, token_hash, token_prefix, "
            + "expires_at) VALUES (?, 'x', ?, ?, ?)", user, ExternalConnectionService.hash(token),
            token.substring(0, ExternalConnectionCodes.DISPLAY_PREFIX_LENGTH), expiresAt);
        return token;
    }

    private int status(String token) {
        return mcp.raw(token == null ? null : "Bearer " + token, "tools/list", Map.of()).getStatus();
    }

    @Test
    void initializeAndToolsListAnswerWithTheDraftingTools() {
        String token = connect(INSTRUCTOR);

        JsonNode init = mcp.initialize(token);
        assertThat(init.path("serverInfo").path("name").asText()).isEqualTo("chan-academy");
        assertThat(init.path("instructions").asText()).contains("Chốt bước").contains("lesson content");

        List<String> names = new ArrayList<>();
        mcp.result(token, "tools/list", Map.of()).path("tools").forEach(tool -> {
            names.add(tool.path("name").asText());
            assertThat(tool.path("description").asText()).isNotBlank();
        });
        assertThat(names).containsExactlyInAnyOrder("start_course_drafting", "get_reference_data",
            "get_drafting_session", "propose_step", "stop_course_drafting");

        JsonNode prompts = mcp.result(token, "prompts/list", Map.of()).path("prompts");
        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0).path("name").asText()).isEqualTo("soan_khoa_hoc");
    }

    @Test
    void missingUnknownRevokedExpiredAndRoleLessTokensGet401() {
        assertThat(status(null)).isEqualTo(401);
        assertThat(status(ExternalConnectionCodes.TOKEN_PREFIX + "nope")).isEqualTo(401);

        String revoked = connect(INSTRUCTOR);
        jdbc.update("UPDATE learning_hub.external_assistant_connection SET revoked_at = now()");
        assertThat(status(revoked)).isEqualTo(401);

        assertThat(status(connectDirectly(INSTRUCTOR, OffsetDateTime.now().minusMinutes(1)))).isEqualTo(401);
        assertThat(status(connectDirectly(LEARNER, OffsetDateTime.now().plusDays(1)))).isEqualTo(401);
        assertThat(status(connectDirectly(REPORT_VIEWER, OffsetDateTime.now().plusDays(1)))).isEqualTo(401);

        // Losing the rights (a temporary password empties them) stops a working connection at once.
        String working = connectDirectly(INSTRUCTOR, OffsetDateTime.now().plusDays(1));
        assertThat(status(working)).isEqualTo(200);
        jdbc.update("UPDATE learning_hub.users SET must_change_password = true WHERE user_id = ?", INSTRUCTOR);
        try {
            assertThat(status(working)).isEqualTo(401);
        } finally {
            jdbc.update("UPDATE learning_hub.users SET must_change_password = false WHERE user_id = ?", INSTRUCTOR);
        }
    }

    @Test
    void aSignInJwtIsRefusedOnTheMcpEndpointAndAConnectionTokenEverywhereElse() throws Exception {
        String jwt = tokens.issue(users.findById(INSTRUCTOR).orElseThrow()).accessToken();
        MockHttpServletResponse withJwt = mcp.raw("Bearer " + jwt, "tools/list", Map.of());
        assertThat(withJwt.getStatus()).isEqualTo(401);

        String token = connect(INSTRUCTOR);
        for (String path : List.of("/api/v1/auth/me", "/api/v1/assistant/external-connections",
            "/api/v1/assistant/conversations")) {
            int answer = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
            assertThat(answer).as(path).isEqualTo(401);
        }
        // The same JWT works where it belongs.
        int me = mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
            .andReturn().getResponse().getStatus();
        assertThat(me).isEqualTo(200);
    }

    @Test
    void startCreatesAnExternalSessionWithNoDocumentAndReturnsTheEditorLink() {
        String token = connect(INSTRUCTOR);

        JsonNode started = mcp.ok(token, "start_course_drafting", Map.of("source_name", "Quy trình mở tài khoản.pdf"))
            .data();
        long id = started.path("sessionId").asLong();

        assertThat(started.path("editorUrl").asText()).isEqualTo("https://lms.example/admin/courses/new?session=" + id);
        assertThat(started.path("waitingStep").asInt()).isEqualTo(1);
        assertThat(started.path("status").asText()).isEqualTo("active");
        Map<String, Object> row = jdbc.queryForMap("SELECT owner_user_id, proposer, source_name, source_document_id "
            + "FROM learning_hub.course_drafting_sessions WHERE session_id = ?", id);
        assertThat(row).containsEntry("owner_user_id", INSTRUCTOR).containsEntry("proposer", "external")
            .containsEntry("source_name", "Quy trình mở tài khoản.pdf").containsEntry("source_document_id", null);
        assertThat(jdbc.queryForObject("SELECT state FROM learning_hub.course_drafting_steps WHERE session_id = ?",
            String.class, id)).isEqualTo("proposing");

        // The page's own session view shows who proposes and the document's name.
        JsonNode view = api.ok(INSTRUCTOR, get("/api/v1/assistant/drafting-sessions/{id}", id));
        assertThat(view.path("proposer").asText()).isEqualTo("external");
        assertThat(view.path("sourceName").asText()).isEqualTo("Quy trình mở tài khoản.pdf");
        assertThat(view.path("source").isNull()).isTrue();

        // The call was seen: "lần dùng cuối" is set.
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM learning_hub.external_assistant_connection",
            OffsetDateTime.class)).isNotNull();

        mcp.refused(token, "start_course_drafting", Map.of("source_name", " "));
    }

    @Test
    void getReadsOnlyTheCallersOwnExternalSessions() {
        String mine = connect(INSTRUCTOR);
        String theirs = connect(ADMIN);
        long own = mcp.ok(mine, "start_course_drafting", Map.of("source_name", "a.md")).data().path("sessionId")
            .asLong();
        long admins = mcp.ok(theirs, "start_course_drafting", Map.of("source_name", "b.md")).data()
            .path("sessionId").asLong();

        JsonNode read = mcp.ok(mine, "get_drafting_session", Map.of("session_id", own)).data();
        assertThat(read.path("sessionId").asLong()).isEqualTo(own);
        assertThat(read.path("steps")).hasSize(1);
        assertThat(read.path("steps").get(0).path("state").asText()).isEqualTo("proposing");

        assertThat(mcp.refused(mine, "get_drafting_session", Map.of("session_id", admins)).text())
            .contains("not found");
        assertThat(mcp.refused(mine, "get_drafting_session", Map.of("session_id", own + 1000)).text())
            .contains("not found");
        // A session of the in-house assistant is not the external assistant's either.
        long inHouse = jdbc.queryForObject("INSERT INTO learning_hub.course_drafting_sessions (owner_user_id, "
            + "owner_org_id, current_step, status) SELECT owner_user_id, owner_org_id, 1, 'active' "
            + "FROM learning_hub.course_drafting_sessions WHERE session_id = ? RETURNING session_id", Long.class, own);
        assertThat(mcp.refused(mine, "get_drafting_session", Map.of("session_id", inHouse)).text())
            .contains("not found");
    }

    @Test
    void referenceDataHoldsIdsAndNamesOnly() {
        String token = connect(INSTRUCTOR);

        JsonNode data = mcp.ok(token, "get_reference_data", Map.of()).data();

        assertThat(data.path("programmes").isArray()).isTrue();
        assertThat(data.path("modules").isArray()).isTrue();
        assertThat(data.path("competencies").isArray()).isTrue();
        data.path("programmes").forEach(item -> assertThat(fieldNames(item))
            .containsExactlyInAnyOrder("id", "name", "fieldId"));
        data.path("modules").forEach(item -> assertThat(fieldNames(item))
            .containsExactlyInAnyOrder("id", "name", "fieldId"));
        data.path("competencies").forEach(item -> assertThat(fieldNames(item))
            .containsExactlyInAnyOrder("id", "name"));
        // The instructor fixture may assign to no unit; whatever is listed has keys and names only.
        assertThat(data.path("audience").isArray()).isTrue();
        data.path("audience").forEach(item -> assertThat(fieldNames(item))
            .containsExactlyInAnyOrder("key", "type", "name", "canIncludeSubunits"));
        assertThat(data.path("levels")).hasSize(3);
    }

    @Test
    void stopEndsTheSessionAndKeepsWhatWasWritten() {
        String token = connect(INSTRUCTOR);
        long id = mcp.ok(token, "start_course_drafting", Map.of("source_name", "a.md")).data().path("sessionId")
            .asLong();

        JsonNode stopped = mcp.ok(token, "stop_course_drafting", Map.of("session_id", id)).data();

        assertThat(stopped.path("status").asText()).isEqualTo("manual");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_drafting_steps WHERE session_id = ?",
            Integer.class, id)).isEqualTo(1);
        // Stopping again changes nothing.
        assertThat(mcp.ok(token, "stop_course_drafting", Map.of("session_id", id)).data().path("status").asText())
            .isEqualTo("manual");
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
