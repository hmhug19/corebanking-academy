package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.com.coreacademy.assistant.conversation.ConversationRetentionJob;
import vn.com.coreacademy.knowledge.ai.ChatMessage;
import vn.com.coreacademy.knowledge.ai.ChatTurn;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Conversations through the real controllers and database; only Ollama is mocked. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ConversationControllerTest extends KnowledgeIntegrationTest {
    private static final String ME = "000004";
    private static final String THEM = "000005";
    private static final String CONVERSATIONS = "/api/v1/assistant/conversations";
    private static final String AGENT = "/api/v1/assistant/agent/stream";
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final ObjectMapper JSON = new ObjectMapper();

    @MockitoBean
    private OllamaClient ollama;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationRetentionJob retention;

    // Other test classes share this database and leave conversations behind, so start empty as well as finish empty.
    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM learning_hub.assistant_turns");
        jdbc.update("DELETE FROM learning_hub.assistant_conversations");
    }

    private void modelAnswers(String text) throws Exception {
        doReturn(true).when(ollama).isAvailable();
        doReturn(new ChatTurn(text, List.of())).when(ollama).chatWithTools(anyList(), anyList(), any(), any());
    }

    /** Runs one agent turn and returns the id its first line named. */
    private long ask(String user, Long conversationId, String message) throws Exception {
        String body = JSON.writeValueAsString(JSON.createObjectNode().put("message", message)
            .set("conversationId", conversationId == null ? JSON.nullNode() : JSON.valueToTree(conversationId)));
        MvcResult started = mvc.perform(post(AGENT).with(as(user)).accept(NDJSON, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(request().asyncStarted()).andReturn();
        MvcResult result = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        String first = result.getResponse().getContentAsString(StandardCharsets.UTF_8).lines().findFirst().orElseThrow();
        JsonNode event = JSON.readTree(first);
        assertThat(event.path("type").asText()).isEqualTo("conversation");
        return event.path("conversationId").asLong();
    }

    @Test
    void aTurnCreatesAConversationTheOwnerCanListAndReadAndNobodyElseCan() throws Exception {
        modelAnswers("Chào bạn");

        long id = ask(ME, null, "Xin chào trợ lý");

        mvc.perform(get(CONVERSATIONS).with(as(ME)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(id))
            .andExpect(jsonPath("$.data.items[0].title").value("Xin chào trợ lý"));
        mvc.perform(get(CONVERSATIONS + "/" + id).with(as(ME)))
            .andExpect(jsonPath("$.data.turns.length()").value(1))
            .andExpect(jsonPath("$.data.turns[0].question").value("Xin chào trợ lý"))
            .andExpect(jsonPath("$.data.turns[0].answer").value("Chào bạn"))
            .andExpect(jsonPath("$.data.turns[0].surface").value("AGENT"))
            .andExpect(jsonPath("$.data.turns[0].status").value("done"));

        mvc.perform(get(CONVERSATIONS).with(as(THEM)))
            .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get(CONVERSATIONS + "/" + id).with(as(THEM)))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-CONVERSATION_NOT_FOUND"));
        mvc.perform(patch(CONVERSATIONS + "/" + id).with(as(THEM)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Của tôi\"}"))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-CONVERSATION_NOT_FOUND"));
        mvc.perform(delete(CONVERSATIONS + "/" + id).with(as(THEM)))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-CONVERSATION_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_conversations", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void theSecondQuestionInAConversationSendsTheFirstTurnToTheModelAsHistory() throws Exception {
        modelAnswers("CIF là mã khách hàng.");
        long id = ask(ME, null, "CIF là gì?");

        long again = ask(ME, id, "Còn CIF doanh nghiệp?");

        assertThat(again).isEqualTo(id);
        ArgumentCaptor<List<ChatMessage>> sent = ArgumentCaptor.captor();
        verify(ollama, org.mockito.Mockito.times(2)).chatWithTools(sent.capture(), anyList(), any(), any());
        List<ChatMessage> second = sent.getAllValues().get(1);
        assertThat(second).extracting(ChatMessage::role).containsExactly("system", "user", "assistant", "user");
        assertThat(second.get(1).content()).isEqualTo("CIF là gì?");
        assertThat(second.get(2).content()).isEqualTo("CIF là mã khách hàng.");
        mvc.perform(get(CONVERSATIONS + "/" + id).with(as(ME)))
            .andExpect(jsonPath("$.data.turns.length()").value(2));
    }

    @Test
    void renameChangesTheTitleAndSearchFindsItByTitleOnly() throws Exception {
        modelAnswers("ok");
        long id = ask(ME, null, "Hỏi về quy trình EOD");
        ask(ME, null, "Chuyện khác hẳn");

        mvc.perform(patch(CONVERSATIONS + "/" + id).with(as(ME)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Quy trình cuối ngày\"}"))
            .andExpect(jsonPath("$.data.title").value("Quy trình cuối ngày"));

        mvc.perform(get(CONVERSATIONS).param("q", "cuối ngày").with(as(ME)))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(id));
        mvc.perform(get(CONVERSATIONS).param("q", "ok").with(as(ME)))
            .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void deleteRemovesTheConversationAndItsTurnsAndDeleteAllRemovesOnlyTheCallers() throws Exception {
        modelAnswers("ok");
        long mine = ask(ME, null, "Của tôi một");
        ask(ME, null, "Của tôi hai");
        ask(THEM, null, "Của họ");

        mvc.perform(delete(CONVERSATIONS + "/" + mine).with(as(ME)))
            .andExpect(jsonPath("$.status").value("SUCCESS"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_turns WHERE conversation_id = ?",
            Integer.class, mine)).isZero();
        mvc.perform(get(CONVERSATIONS + "/" + mine).with(as(ME)))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-CONVERSATION_NOT_FOUND"));

        mvc.perform(delete(CONVERSATIONS).with(as(ME))).andExpect(jsonPath("$.status").value("SUCCESS"));

        mvc.perform(get(CONVERSATIONS).with(as(ME))).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get(CONVERSATIONS).with(as(THEM))).andExpect(jsonPath("$.data.totalElements").value(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_turns", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void theRetentionJobDeletesOnlyConversationsInactiveLongerThanTheSetting() throws Exception {
        modelAnswers("ok");
        long old = ask(ME, null, "Cũ");
        long recent = ask(ME, null, "Mới");
        jdbc.update("UPDATE learning_hub.assistant_conversations SET last_activity_time = now() - interval '91 days' "
            + "WHERE conversation_id = ?", old);
        jdbc.update("UPDATE learning_hub.assistant_conversations SET last_activity_time = now() - interval '89 days' "
            + "WHERE conversation_id = ?", recent);

        retention.purge();

        assertThat(jdbc.queryForList("SELECT conversation_id FROM learning_hub.assistant_conversations", Long.class))
            .containsExactly(recent);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_turns WHERE conversation_id = ?",
            Integer.class, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_turns WHERE conversation_id = ?",
            Integer.class, recent)).isEqualTo(1);
    }

    @Test
    void aCallerWithoutTheAssistantFunctionIsRefused() throws Exception {
        jdbc.update("UPDATE learning_hub.users SET must_change_password = true WHERE user_id = ?", ME);
        try {
            mvc.perform(get(CONVERSATIONS).with(as(ME)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
            mvc.perform(delete(CONVERSATIONS).with(as(ME)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
        } finally {
            jdbc.update("UPDATE learning_hub.users SET must_change_password = false WHERE user_id = ?", ME);
        }
    }

    @Test
    void everyEndpointRequiresAuthentication() throws Exception {
        mvc.perform(get(CONVERSATIONS)).andExpect(status().isUnauthorized());
        mvc.perform(delete(CONVERSATIONS)).andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor as(String userId) {
        return jwt().jwt(token -> token.subject(userId));
    }
}
