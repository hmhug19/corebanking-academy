package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.com.coreacademy.knowledge.ai.ChatTurn;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.ToolCall;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The profile change end to end on the real schema and the seeded demo users: the model proposes, nothing changes
 * until the owner confirms, and the change and its audit entry happen exactly once. Only Ollama is mocked.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AgentProfileFlowIT extends KnowledgeIntegrationTest {
    private static final String AGENT = "/api/v1/assistant/agent";
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LEARNER = "000004";
    private static final String ADMIN = "000002";
    private static final String NEW_POSITION = "Chuyên viên chính";

    @MockitoBean
    private OllamaClient ollama;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String originalPosition;

    @AfterEach
    void restoreTheSeededProfile() {
        if (originalPosition != null) {
            jdbc.update("UPDATE learning_hub.users SET position_name = ? WHERE user_id = ?", originalPosition, LEARNER);
        }
    }

    private String position() {
        return jdbc.queryForObject("SELECT position_name FROM learning_hub.users WHERE user_id = ?", String.class,
            LEARNER);
    }

    private int audits() {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.audit_log WHERE action = 'USER_PROFILE_UPDATED' "
            + "AND actor_id = ?", Integer.class, LEARNER);
    }

    private String proposeAPositionChange() throws Exception {
        doReturn(true).when(ollama).isAvailable();
        doAnswer(invocation -> new ChatTurn("", List.of(ToolCall.of("update_profile",
            JSON.createObjectNode().put("positionName", NEW_POSITION))))).when(ollama)
            .chatWithTools(anyList(), anyList(), any(), any());

        MvcResult started = mvc.perform(post(AGENT + "/stream").with(as(LEARNER)).accept(NDJSON, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Đổi chức danh của tôi thành Chuyên viên chính\",\"skill\":\"ho_so\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        MvcResult result = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();

        List<String> all = result.getResponse().getContentAsString(StandardCharsets.UTF_8).lines().toList();
        assertThat(all.get(0)).startsWith("{\"type\":\"conversation\"");
        List<String> lines = all.subList(1, all.size());
        assertThat(lines).hasSize(3);
        JsonNode card = JSON.readTree(lines.get(1));
        assertThat(card.path("type").asText()).isEqualTo("action_required");
        assertThat(card.path("action").path("changes").get(0).path("from").asText()).isEqualTo(originalPosition);
        assertThat(card.path("action").path("changes").get(0).path("to").asText()).isEqualTo(NEW_POSITION);
        return card.path("action").path("id").asText();
    }

    @Test
    void theRowChangesOnlyWhenTheOwnerConfirmsAndOnlyOnce() throws Exception {
        originalPosition = position();
        int auditsBefore = audits();

        String actionId = proposeAPositionChange();

        assertThat(position()).isEqualTo(originalPosition);
        assertThat(audits()).isEqualTo(auditsBefore);

        mvc.perform(post(AGENT + "/actions/" + actionId + "/confirm").with(as(ADMIN)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.messageCode").value("ASSISTANT-ACTION_NOT_FOUND"));
        assertThat(position()).isEqualTo(originalPosition);

        mvc.perform(post(AGENT + "/actions/" + actionId + "/confirm").with(as(LEARNER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.tool").value("update_profile"))
            .andExpect(jsonPath("$.data.message").value("Đã cập nhật hồ sơ."))
            .andExpect(jsonPath("$.data.data.user.positionName").value(NEW_POSITION));
        assertThat(position()).isEqualTo(NEW_POSITION);
        assertThat(audits()).isEqualTo(auditsBefore + 1);

        mvc.perform(post(AGENT + "/actions/" + actionId + "/confirm").with(as(LEARNER)))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-ACTION_NOT_FOUND"));
        assertThat(audits()).isEqualTo(auditsBefore + 1);
    }

    @Test
    void aCancelledProposalNeverChangesTheRow() throws Exception {
        originalPosition = position();
        String actionId = proposeAPositionChange();

        mvc.perform(post(AGENT + "/actions/" + actionId + "/cancel").with(as(LEARNER)))
            .andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(post(AGENT + "/actions/" + actionId + "/confirm").with(as(LEARNER)))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-ACTION_NOT_FOUND"));

        assertThat(position()).isEqualTo(originalPosition);
    }

    @Test
    void aProfileChangedAfterTheProposalIsStaleAndStaysAsItIs() throws Exception {
        originalPosition = position();
        String actionId = proposeAPositionChange();
        jdbc.update("UPDATE learning_hub.users SET position_name = 'Đã đổi nơi khác' WHERE user_id = ?", LEARNER);

        mvc.perform(post(AGENT + "/actions/" + actionId + "/confirm").with(as(LEARNER)))
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-ACTION_STALE"));

        assertThat(position()).isEqualTo("Đã đổi nơi khác");
    }

    @Test
    void aUserWithoutTheAssistantFunctionIsRefusedOnEveryEndpoint() throws Exception {
        originalPosition = position();
        String actionId = proposeAPositionChange();
        // A temporary password empties the user's rights, AI_ASSISTANT included.
        jdbc.update("UPDATE learning_hub.users SET must_change_password = true WHERE user_id = ?", LEARNER);
        try {
            mvc.perform(post(AGENT + "/stream").with(as(LEARNER)).accept(NDJSON, MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"message\":\"Xin chào\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
            mvc.perform(post(AGENT + "/actions/" + actionId + "/cancel").with(as(LEARNER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
            mvc.perform(post(AGENT + "/actions/" + actionId + "/confirm").with(as(LEARNER)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
        } finally {
            jdbc.update("UPDATE learning_hub.users SET must_change_password = false WHERE user_id = ?", LEARNER);
        }

        assertThat(position()).isEqualTo(originalPosition);
        mvc.perform(post(AGENT + "/actions/" + actionId + "/cancel").with(as(LEARNER)))
            .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    private static RequestPostProcessor as(String userId) {
        return jwt().jwt(token -> token.subject(userId));
    }
}
