package vn.com.coreacademy.assistant.external;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;

/** The limits of the Trợ lý ngoài (calls per minute, body size, sessions going at once) and the audit of its calls. */
class ExternalLimitsIT extends ExternalAssistantIT {
    private static final String START = ExternalAssistantTools.START;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExternalAssistantProperties properties;

    private ApiClient api;
    private McpClient mcp;
    private String token;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        mcp = new McpClient(mvc, json);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM learning_hub.audit_log WHERE action = ?", ExternalAssistantTools.AUDIT_ACTION);
        jdbc.update("DELETE FROM learning_hub.external_assistant_connection");
        jdbc.update("DELETE FROM learning_hub.course_drafting_steps");
        jdbc.update("DELETE FROM learning_hub.course_drafting_sessions");
        token = connect();
    }

    private String connect() {
        return api.ok(INSTRUCTOR, api.post(Map.of("name", "Claude Code", "validDays", 30),
            ExternalConnectionIT.CONNECTIONS)).path("token").asText();
    }

    private int listTools(String connection) {
        return mcp.raw("Bearer " + connection, "tools/list", Map.of()).getStatus();
    }

    @Test
    void theCallAfterTheMinutesLimitIsRefusedWithAClearMessage() throws Exception {
        int limit = properties.callsPerMinute();
        assertThat(limit).isEqualTo(60);
        // All the calls inside one calendar minute: begin again if the minute turned meanwhile.
        for (int round = 0; round < 2; round++) {
            String connection = connect();
            Instant minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
            for (int i = 0; i < limit; i++) {
                assertThat(listTools(connection)).as("call " + (i + 1)).isEqualTo(200);
            }
            MockHttpServletResponse refused = mcp.raw("Bearer " + connection, "tools/list", Map.of());
            if (!minute.equals(Instant.now().truncatedTo(ChronoUnit.MINUTES))) {
                continue;
            }
            assertThat(refused.getStatus()).isEqualTo(429);
            assertThat(Long.parseLong(refused.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
            JsonNode body = mcp.body(refused, 429);
            assertThat(body.path("messageCode").asText()).isEqualTo("CORE-TOO_MANY_REQUESTS");
            assertThat(body.path("errors").get(0).path("message").asText()).contains("60 calls a minute");
            // Another connection of the same user has its own count.
            assertThat(listTools(token)).isEqualTo(200);
            return;
        }
        throw new AssertionError("the minute kept turning");
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsParsed() throws Exception {
        String padding = "x".repeat((int) properties.maxBody().toBytes());
        // Not even JSON: a parser would answer a JSON-RPC parse error, the limit answers first.
        MockHttpServletResponse refused = mcp.raw("Bearer " + token, "{" + padding);
        assertThat(refused.getStatus()).isEqualTo(413);
        assertThat(mcp.body(refused, 413).path("messageCode").asText()).isEqualTo("CORE-PAYLOAD_TOO_LARGE");
        // Nothing reached a tool.
        assertThat(audits()).isEmpty();

        // A body just under the limit goes through as usual.
        Map<String, Object> basics = new HashMap<>();
        basics.put("title", "x");
        basics.put("padding", "y".repeat(200 * 1024));
        assertThat(mcp.call(token, ExternalAssistantTools.REFERENCE_DATA, basics).error()).isFalse();
    }

    @Test
    void aFourthActiveSessionIsRefusedAndStoppingOneFreesAPlace() {
        int max = properties.maxActiveSessions();
        assertThat(max).isEqualTo(3);
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < max; i++) {
            ids.add(mcp.ok(token, START, Map.of("source_name", "tai-lieu-" + i + ".pdf")).data().path("sessionId")
                .asLong());
        }

        McpClient.ToolResult refused = mcp.refused(token, START, Map.of("source_name", "thu-tu.pdf"));
        assertThat(refused.text()).contains("already have 3").contains("stop_course_drafting");

        mcp.ok(token, ExternalAssistantTools.STOP, Map.of("session_id", ids.get(0)));
        assertThat(mcp.ok(token, START, Map.of("source_name", "thu-tu.pdf")).data().path("status").asText())
            .isEqualTo("active");
    }

    @Test
    void everyToolCallWritesOneAuditRowWithoutTheProposalText() {
        String marker = "NOI-DUNG-BI-MAT-" + System.nanoTime();
        long sessionId = mcp.ok(token, START, Map.of("source_name", "quy-trinh.pdf")).data().path("sessionId")
            .asLong();
        Map<String, Object> invalid = new HashMap<>();
        invalid.put("title", marker);
        invalid.put("summary", marker);
        invalid.put("objectives", List.of(marker));
        invalid.put("level", "expert");
        invalid.put("defaultRequirement", "mandatory");
        mcp.refused(token, ExternalAssistantTools.PROPOSE, Map.of("session_id", sessionId, "step", 1,
            "proposal", invalid));
        mcp.refused(token, ExternalAssistantTools.GET_SESSION, Map.of("session_id", "abc"));

        List<Map<String, Object>> rows = audits();
        assertThat(rows).hasSize(3);
        long connectionId = jdbc.queryForObject(
            "SELECT connection_id FROM learning_hub.external_assistant_connection", Long.class);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("actor_id")).isEqualTo(INSTRUCTOR);
            assertThat(row.get("details").toString()).doesNotContain(marker).doesNotContain("quy-trinh");
        }
        JsonNode started = details(rows.get(0));
        assertThat(started.path("tool").asText()).isEqualTo(START);
        assertThat(started.path("outcome").asText()).isEqualTo("ok");
        assertThat(started.path("sessionId").asLong()).isEqualTo(sessionId);
        assertThat(started.path("connectionId").asLong()).isEqualTo(connectionId);
        assertThat(started.path("durationMs").isIntegralNumber()).isTrue();
        assertThat(rows.get(0).get("entity_id")).isEqualTo(Long.toString(sessionId));

        JsonNode proposed = details(rows.get(1));
        assertThat(proposed.path("tool").asText()).isEqualTo(ExternalAssistantTools.PROPOSE);
        assertThat(proposed.path("outcome").asText()).isEqualTo("refused");
        assertThat(proposed.path("step").asInt()).isEqualTo(1);
        assertThat(proposed.path("sessionId").asLong()).isEqualTo(sessionId);

        JsonNode read = details(rows.get(2));
        assertThat(read.path("outcome").asText()).isEqualTo("refused");
        assertThat(read.path("sessionId").isNull()).isTrue();
    }

    private List<Map<String, Object>> audits() {
        return jdbc.queryForList("SELECT actor_id, entity_type, entity_id, details::text AS details "
            + "FROM learning_hub.audit_log WHERE action = ? ORDER BY audit_id", ExternalAssistantTools.AUDIT_ACTION);
    }

    private JsonNode details(Map<String, Object> row) {
        try {
            return json.readTree(row.get("details").toString());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
