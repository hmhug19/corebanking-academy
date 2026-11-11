package vn.com.coreacademy.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.NewChunk;
import vn.com.coreacademy.knowledge.repository.KnowledgeDocumentRepository;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class KnowledgeChatControllerTest extends KnowledgeIntegrationTest {
    private static final String CHAT = "/api/v1/knowledge/chat";
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    @MockitoBean
    private OllamaClient ollama;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private DocumentChunkRepository chunks;

    @BeforeEach
    void knowledgeBaseWithOneDocument() {
        jdbc.update("DELETE FROM learning_hub.knowledge_document_chunks");
        jdbc.update("DELETE FROM learning_hub.knowledge_documents");
        KnowledgeDocument document = new KnowledgeDocument();
        document.setUploadedBy("000005");
        document.setScope(DocumentScope.GLOBAL);
        document.setFileName("Quy-trinh-EOD.pdf");
        document.setMimeType("application/pdf");
        document.setSizeBytes(1);
        document.setStorageKey("00000000-0000-0000-0000-000000000002");
        long id = documents.saveAndFlush(document).getId();
        chunks.insertAll(id, List.of(new NewChunk(0, "CIF là mã khách hàng duy nhất.", 2, 4, 6, 12, axis(0))));
    }

    @Test
    void chatAnswersFromTheMatchingChunkAndCitesItsFileAndLines() throws Exception {
        doReturn(List.of(axis(0))).when(ollama).embed(anyList());
        doReturn("CIF là mã khách hàng duy nhất [1].").when(ollama).chat(anyList(), any());

        mvc.perform(post(CHAT).with(as("000004")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"CIF là gì?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.found").value(true))
            .andExpect(jsonPath("$.data.answer").value("CIF là mã khách hàng duy nhất [1]."))
            .andExpect(jsonPath("$.data.citations.length()").value(1))
            .andExpect(jsonPath("$.data.citations[0].n").value(1))
            .andExpect(jsonPath("$.data.citations[0].fileName").value("Quy-trinh-EOD.pdf"))
            .andExpect(jsonPath("$.data.citations[0].page").value(2))
            .andExpect(jsonPath("$.data.citations[0].lineStart").value(4))
            .andExpect(jsonPath("$.data.citations[0].lineEnd").value(6))
            .andExpect(jsonPath("$.data.citations[0].snippet").value("CIF là mã khách hàng duy nhất."));
    }

    @Test
    void chatReturnsTheFixedSentenceWithoutCitationsWhenNothingIsRelevant() throws Exception {
        doReturn(List.of(axis(5))).when(ollama).embed(anyList());

        mvc.perform(post(CHAT).with(as("000004")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Thời tiết hôm nay?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.found").value(false))
            .andExpect(jsonPath("$.data.answer").value("Không tìm thấy thông tin này trong tài liệu."))
            .andExpect(jsonPath("$.data.citations.length()").value(0));
        verify(ollama, never()).chat(anyList(), any());
    }

    @Test
    void chatRefusesABlankOrTooLongQuestion() throws Exception {
        for (String question : List.of("   ", "x".repeat(301))) {
            mvc.perform(post(CHAT).with(as("000004")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"" + question + "\"}"))
                .andExpect(jsonPath("$.status").value("ERROR"));
        }
    }

    @Test
    void chatReportsAiUnavailableAsAnEnvelope() throws Exception {
        doThrow(new OllamaUnavailableException("down", null)).when(ollama).embed(anyList());

        mvc.perform(post(CHAT).with(as("000004")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"CIF là gì?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.messageCode").value("KNOWLEDGE-AI_UNAVAILABLE"));
    }

    @Test
    void chatRequiresAuthentication() throws Exception {
        mvc.perform(post(CHAT).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"CIF là gì?\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void streamWritesOneJsonLineAtATimeAndEndsWithDoneAndCitations() throws Exception {
        doReturn(List.of(axis(0))).when(ollama).embed(anyList());
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(2);
            onDelta.accept("CIF là ");
            onDelta.accept("mã khách hàng [1].");
            return null;
        }).when(ollama).chatStream(anyList(), any(), any(), any());

        MvcResult started = mvc.perform(post(CHAT + "/stream").with(as("000004"))
                .accept(NDJSON, MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"CIF là gì?\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        MvcResult result = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("application/x-ndjson");
        assertThat(result.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        List<String> all = result.getResponse().getContentAsString(StandardCharsets.UTF_8).lines().toList();
        assertThat(all.get(0)).matches("\\{\"type\":\"conversation\",\"conversationId\":\\d+}");
        List<String> lines = all.subList(1, all.size());
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo("{\"type\":\"retrieved\",\"count\":1}");
        assertThat(lines.get(1)).isEqualTo("{\"type\":\"delta\",\"text\":\"CIF là \"}");
        assertThat(lines.get(2)).isEqualTo("{\"type\":\"delta\",\"text\":\"mã khách hàng [1].\"}");
        assertThat(lines.get(3)).startsWith("{\"type\":\"done\",\"found\":true,\"answer\":\"CIF là mã khách hàng [1].\",\"citations\":[{\"n\":1,")
            .contains("\"fileName\":\"Quy-trinh-EOD.pdf\"").contains("\"page\":2").contains("\"lineStart\":4");
    }

    @Test
    void streamRefusalsBeforeTheFirstLineAreOrdinaryEnvelopes() throws Exception {
        doThrow(new OllamaUnavailableException("down", null)).when(ollama).embed(anyList());

        mvc.perform(post(CHAT + "/stream").with(as("000004")).accept(NDJSON, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"CIF là gì?\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.messageCode").value("KNOWLEDGE-AI_UNAVAILABLE"));
    }

    @Test
    void aCallerWithoutTheAssistantFunctionIsRefusedOnBothChatEndpointsAndTheModelIsNotCalled() throws Exception {
        // A temporary password empties the user's rights, AI_ASSISTANT included.
        jdbc.update("UPDATE learning_hub.users SET must_change_password = true WHERE user_id = ?", "000004");
        try {
            mvc.perform(post(CHAT).with(as("000004")).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"CIF là gì?\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
            mvc.perform(post(CHAT + "/stream").with(as("000004")).accept(NDJSON, MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"CIF là gì?\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
        } finally {
            jdbc.update("UPDATE learning_hub.users SET must_change_password = false WHERE user_id = ?", "000004");
        }

        verify(ollama, never()).embed(anyList());
        verify(ollama, never()).chat(anyList(), any());
    }

    private static RequestPostProcessor as(String userId) {
        return jwt().jwt(token -> token.subject(userId));
    }

    private static float[] axis(int index) {
        float[] vector = new float[1024];
        vector[index] = 1f;
        return vector;
    }
}
