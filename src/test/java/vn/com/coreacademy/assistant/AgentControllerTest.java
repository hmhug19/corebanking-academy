package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.com.coreacademy.assistant.action.PendingAction;
import vn.com.coreacademy.assistant.action.PendingActionStore;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.dto.ActionResultView;
import vn.com.coreacademy.assistant.dto.PendingActionView.Change;
import vn.com.coreacademy.assistant.tool.AgentTool;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.knowledge.ai.ChatTurn;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.ToolCall;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;
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
 * The real Spring context and database; only Ollama is mocked and two stub tools stand in for the real ones. MockMvc's
 * printing is off: it reads the response headers while the stream body is still writing on its own thread.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AgentControllerTest extends KnowledgeIntegrationTest {
    private static final String AGENT = "/api/v1/assistant/agent";
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class StubTools {
        /** The stub tools belong to no real skill, so the document skill is told to offer them. */
        @Bean
        @org.springframework.context.annotation.Primary
        vn.com.coreacademy.assistant.skill.SkillCatalog stubSkills() {
            return new vn.com.coreacademy.assistant.skill.SkillCatalog("base", List.of(
                new vn.com.coreacademy.assistant.skill.Skill("qa_tai_lieu", "hỏi đáp",
                    List.of("stub_read", "stub_change"), List.of(), "system", "", "")), "test");
        }

        @Bean
        AgentTool stubRead() {
            return new StubTool("stub_read") {
                @Override
                public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
                    return new ToolOutcome.Result("stub result");
                }
            };
        }

        @Bean
        AgentTool stubChange(PendingActionStore store) {
            return new StubTool("stub_change") {
                @Override
                public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
                    PendingAction action = store.add(context.userId(), name(), arguments, "Đề xuất thử",
                        List.of(new Change("positionName", "Chức danh", null, "Mới")));
                    return new ToolOutcome.ActionRequired(action, "Vui lòng xác nhận.");
                }

                @Override
                public ActionResultView execute(PendingAction action, AgentContext context) {
                    return new ActionResultView(action.id(), name(), "Đã chạy.", null);
                }
            };
        }
    }

    private abstract static class StubTool implements AgentTool {
        private final String name;

        StubTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean availableTo(AgentContext context) {
            return true;
        }

        @Override
        public ToolDefinition definition(AgentContext context) {
            return new ToolDefinition(name, "stub", JSON.createObjectNode().put("type", "object"));
        }
    }

    @MockitoBean
    private OllamaClient ollama;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PendingActionStore store;

    private List<String> lines(String userId, String body) throws Exception {
        MvcResult started = mvc.perform(post(AGENT + "/stream").with(as(userId)).accept(NDJSON, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(request().asyncStarted()).andReturn();
        MvcResult result = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentType()).startsWith("application/x-ndjson");
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        List<String> all = result.getResponse().getContentAsString(StandardCharsets.UTF_8).lines().toList();
        // Every stream opens with the conversation it is stored in; the tests below read what follows.
        assertThat(all.get(0)).matches("\\{\"type\":\"conversation\",\"conversationId\":\\d+}");
        return all.subList(1, all.size());
    }

    private static String question(String text) {
        return "{\"message\":\"" + text + "\"}";
    }

    @Test
    void streamWritesTheToolLineBeforeTheDeltasAndEndsWithDone() throws Exception {
        doReturn(true).when(ollama).isAvailable();
        int[] calls = {0};
        doAnswer(invocation -> {
            if (calls[0]++ == 0) {
                return new ChatTurn("", List.of(ToolCall.of("stub_read", JSON.createObjectNode())));
            }
            Consumer<String> onDelta = invocation.getArgument(3);
            onDelta.accept("Xin ");
            onDelta.accept("chào");
            return new ChatTurn("Xin chào", List.of());
        }).when(ollama).chatWithTools(anyList(), anyList(), any(), any());

        List<String> lines = lines("000004", question("Xin chào"));

        assertThat(lines.subList(0, 3)).containsExactly(
            "{\"type\":\"tool\",\"name\":\"stub_read\"}",
            "{\"type\":\"delta\",\"text\":\"Xin \"}",
            "{\"type\":\"delta\",\"text\":\"chào\"}");
        assertThat(lines.get(3)).matches("\\{\"type\":\"done\",\"answer\":\"Xin chào\",\"citations\":\\[],\"turnId\":\\d+}");
    }

    @Test
    void anActionRequiredEventCarriesTheCardWithAnIsoExpiryAndNoOwner() throws Exception {
        doReturn(true).when(ollama).isAvailable();
        doAnswer(invocation -> new ChatTurn("", List.of(ToolCall.of("stub_change", JSON.createObjectNode()))))
            .when(ollama).chatWithTools(anyList(), anyList(), any(), any());

        List<String> lines = lines("000004", question("đổi chức danh"));

        assertThat(lines).hasSize(3);
        JsonNode card = JSON.readTree(lines.get(1)).path("action");
        assertThat(lines.get(1)).startsWith("{\"type\":\"action_required\",\"action\":{\"id\":");
        assertThat(card.path("tool").asText()).isEqualTo("stub_change");
        assertThat(card.path("expiresAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z");
        assertThat(card.has("userId")).isFalse();
        assertThat(card.has("arguments")).isFalse();
        assertThat(lines.get(2))
            .matches("\\{\"type\":\"done\",\"answer\":\"Vui lòng xác nhận.\",\"citations\":\\[],\"turnId\":\\d+}");
    }

    @Test
    void invalidRequestsAreEnvelopesNotStreams() throws Exception {
        doReturn(true).when(ollama).isAvailable();
        List<String> bodies = List.of(
            "{}",
            "{\"message\":null}",
            "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
            question("x".repeat(501)),
            question("   "));

        for (String body : bodies) {
            mvc.perform(post(AGENT + "/stream").with(as("000004")).accept(NDJSON, MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(request().asyncNotStarted())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.messageCode").value("CORE-VALIDATION_FAILED"));
        }
    }

    @Test
    void aConversationIdThatDoesNotExistIsAnEnvelopeNotAStream() throws Exception {
        doReturn(true).when(ollama).isAvailable();

        mvc.perform(post(AGENT + "/stream").with(as("000004")).accept(NDJSON, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":999999999,\"message\":\"hi\"}"))
            .andExpect(status().isOk())
            .andExpect(request().asyncNotStarted())
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-CONVERSATION_NOT_FOUND"));
    }

    @Test
    void anUnreachableOllamaIsAnAiUnavailableEnvelope() throws Exception {
        doReturn(false).when(ollama).isAvailable();

        mvc.perform(post(AGENT + "/stream").with(as("000004")).accept(NDJSON, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(question("Xin chào")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-AI_UNAVAILABLE"));
    }

    @Test
    void everyEndpointRequiresAuthentication() throws Exception {
        mvc.perform(post(AGENT + "/stream").contentType(MediaType.APPLICATION_JSON).content(question("hi")))
            .andExpect(status().isUnauthorized());
        mvc.perform(post(AGENT + "/actions/any/confirm")).andExpect(status().isUnauthorized());
        mvc.perform(post(AGENT + "/actions/any/cancel")).andExpect(status().isUnauthorized());
    }

    @Test
    void confirmRunsTheActionOnceForItsOwnerOnly() throws Exception {
        PendingAction action = store.add("000004", "stub_change", JSON.createObjectNode(), "Đề xuất thử",
            List.of(new Change("positionName", "Chức danh", null, "Mới")));

        mvc.perform(post(AGENT + "/actions/" + action.id() + "/confirm").with(as("000002")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.messageCode").value("ASSISTANT-ACTION_NOT_FOUND"));
        mvc.perform(post(AGENT + "/actions/" + action.id() + "/confirm").with(as("000004")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.actionId").value(action.id()))
            .andExpect(jsonPath("$.data.tool").value("stub_change"))
            .andExpect(jsonPath("$.data.message").value("Đã chạy."));
        mvc.perform(post(AGENT + "/actions/" + action.id() + "/confirm").with(as("000004")))
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-ACTION_NOT_FOUND"))
            .andExpect(jsonPath("$.messageTitle").value("Yêu cầu không còn hiệu lực"));
    }

    @Test
    void cancelDropsTheActionAndAnUnknownIdIsNotFound() throws Exception {
        PendingAction action = store.add("000004", "stub_change", JSON.createObjectNode(), "Đề xuất thử",
            List.of(new Change("positionName", "Chức danh", null, "Mới")));

        mvc.perform(post(AGENT + "/actions/" + action.id() + "/cancel").with(as("000004")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"));
        mvc.perform(post(AGENT + "/actions/" + action.id() + "/confirm").with(as("000004")))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-ACTION_NOT_FOUND"));
        mvc.perform(post(AGENT + "/actions/unknown/cancel").with(as("000004")))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-ACTION_NOT_FOUND"));
    }

    private static RequestPostProcessor as(String userId) {
        return jwt().jwt(token -> token.subject(userId));
    }
}
