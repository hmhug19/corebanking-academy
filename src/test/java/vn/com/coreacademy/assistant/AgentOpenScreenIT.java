package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.knowledge.ai.ChatTurn;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.ToolCall;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** open_screen against the seeded roles: the screens a user is offered are exactly the ones their role grants. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class AgentOpenScreenIT extends KnowledgeIntegrationTest {
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final ObjectMapper JSON = new ObjectMapper();

    @MockitoBean
    private OllamaClient ollama;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KnowledgeProperties knowledge;

    @Autowired
    private AssistantProperties assistant;

    private MvcResult start(String userId) throws Exception {
        doReturn(true).when(ollama).isAvailable();
        int[] calls = {0};
        doAnswer(invocation -> calls[0]++ == 0
            ? new ChatTurn("", List.of(ToolCall.of("open_screen", JSON.createObjectNode()
                .put("functionCode", "USER_MANAGEMENT"))))
            : new ChatTurn("Bạn không có quyền mở màn hình này.", List.of()))
            .when(ollama).chatWithTools(anyList(), anyList(), any(), any(), any());

        return mvc.perform(post("/api/v1/assistant/agent/stream").with(as(userId))
                .accept(NDJSON, MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"mở trang quản lý người dùng\",\"skill\":\"dieu_huong\"}"))
            .andExpect(request().asyncStarted()).andReturn();
    }

    private List<JsonNode> turn(String userId) throws Exception {
        MvcResult result = mvc.perform(asyncDispatch(start(userId))).andExpect(status().isOk()).andReturn();

        List<JsonNode> events = new ArrayList<>();
        for (String line : result.getResponse().getContentAsString(StandardCharsets.UTF_8).lines().toList()) {
            events.add(JSON.readTree(line));
        }
        // Every stream opens with the conversation it is stored in; these tests read what follows.
        assertThat(events.get(0).path("type").asText()).isEqualTo("conversation");
        return events.subList(1, events.size());
    }

    @Test
    void theStreamOutlivesATurnWhoseEveryModelCallTakesTheFullReadTimeout() throws Exception {
        MvcResult started = start("000002");

        Duration slowestTurn = knowledge.ollama().readTimeout().multipliedBy(assistant.maxSteps());
        assertThat(started.getRequest().getAsyncContext().getTimeout()).isGreaterThanOrEqualTo(slowestTurn.toMillis());
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }

    @Test
    void anAdminIsSentToTheUserManagementRoute() throws Exception {
        List<JsonNode> events = turn("000002");

        assertThat(events).extracting(event -> event.path("type").asText()).containsExactly("tool", "navigate", "done");
        assertThat(events.get(1).path("path").asText()).isEqualTo("/admin/users");
        assertThat(events.get(2).path("answer").asText()).isEqualTo("Đã mở trang Quản lý người dùng.");
    }

    @Test
    void aLearnerIsRefusedInWordsAndNothingNavigates() throws Exception {
        List<JsonNode> events = turn("000004");

        assertThat(events).extracting(event -> event.path("type").asText()).doesNotContain("navigate");
        assertThat(events.get(events.size() - 1).path("type").asText()).isEqualTo("done");
    }

    @Test
    void theModelIsOfferedOnlyTheScreensOfTheUsersRole() throws Exception {
        turn("000004");

        ArgumentCaptor<List<ToolDefinition>> offered = ArgumentCaptor.captor();
        verify(ollama, atLeastOnce())
            .chatWithTools(anyList(), offered.capture(), any(), any(), any());
        ToolDefinition openScreen = offered.getAllValues().get(0).stream()
            .filter(definition -> definition.name().equals("open_screen")).findFirst().orElseThrow();
        assertThat(openScreen.description()).contains("PROFILE").doesNotContain("USER_MANAGEMENT")
            .doesNotContain("SYSTEM_AUDIT");
        assertThat(offered.getAllValues().get(0)).extracting(ToolDefinition::name)
            .containsExactly("open_screen");
    }

    private static RequestPostProcessor as(String userId) {
        return jwt().jwt(token -> token.subject(userId));
    }
}
