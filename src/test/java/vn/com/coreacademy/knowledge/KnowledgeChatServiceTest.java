package vn.com.coreacademy.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;
import vn.com.coreacademy.knowledge.ai.OllamaException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.dto.ChatViews.ChatAnswerView;
import vn.com.coreacademy.knowledge.exception.KnowledgeResponseCode;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.service.ChatPromptBuilder;
import vn.com.coreacademy.knowledge.service.KnowledgeChatService;
import vn.com.coreacademy.knowledge.service.NotFoundAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatServiceTest {
    private static final ChunkHit CIF = new ChunkHit(1, 42, "Quy-trinh-EOD.pdf", 3, 13, 27,
        "CIF (Customer Information File) là mã khách hàng duy nhất.", 0.82);
    private static final ChunkHit GL = new ChunkHit(2, 43, "Checklist.md", null, 5, 9,
        "Đối chiếu số dư GL trước khi chạy EOD.", 0.55);
    private static final ChunkHit WEAK = new ChunkHit(3, 44, "Khac.docx", null, 1, 2, "Nội dung không liên quan.",
        0.12);

    private final OllamaClient ollama = mock(OllamaClient.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private KnowledgeChatService service;

    @BeforeEach
    void setUp() {
        KnowledgeProperties properties = new KnowledgeProperties(Path.of("unused"), DataSize.ofMegabytes(20),
            new KnowledgeProperties.Worker(Duration.ofSeconds(2), 1, 3),
            new KnowledgeProperties.Chunk(600, 800, 80), new KnowledgeProperties.Retrieval(5, 0.35),
            new KnowledgeProperties.Ollama("http://localhost:11434", "chat", "embed", Duration.ofSeconds(5),
                Duration.ofSeconds(180), Duration.ofMinutes(10)));
        service = new KnowledgeChatService(ollama, chunks, new ChatPromptBuilder(), properties, json);
        when(ollama.embed(anyList())).thenReturn(List.of(new float[1024]));
    }

    @Test
    void nothingAboveTheThresholdIsNotFoundAndTheModelIsNeverCalled() {
        when(chunks.search(any(), anyInt())).thenReturn(List.of(WEAK));

        ChatAnswerView answer = service.answer("Câu hỏi không có trong tài liệu?");

        assertThat(answer.found()).isFalse();
        assertThat(answer.answer()).isEqualTo("Không tìm thấy thông tin này trong tài liệu.");
        assertThat(answer.citations()).isEmpty();
        verify(ollama, never()).chat(anyList(), any());
        verify(ollama, never()).chatStream(anyList(), any(), any());
    }

    @Test
    void whenTheModelSaysNothingWasFoundThereAreNoCitations() {
        when(chunks.search(any(), anyInt())).thenReturn(List.of(CIF, GL));
        when(ollama.chat(anyList(), any())).thenReturn("  \"Không tìm thấy thông tin này trong tài liệu.\"  ");

        ChatAnswerView answer = service.answer("Hỏi gì đó");

        assertThat(answer.found()).isFalse();
        assertThat(answer.answer()).isEqualTo(NotFoundAnswer.TEXT);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void aNormalAnswerCitesEveryRetrievedChunkWithItsPageAndLines() {
        when(chunks.search(any(), anyInt())).thenReturn(List.of(CIF, WEAK, GL));
        when(ollama.chat(anyList(), any())).thenReturn("CIF là mã khách hàng duy nhất [1].");

        ChatAnswerView answer = service.answer("CIF là gì?");

        assertThat(answer.found()).isTrue();
        assertThat(answer.answer()).isEqualTo("CIF là mã khách hàng duy nhất [1].");
        assertThat(answer.citations()).hasSize(2);
        assertThat(answer.citations().get(0)).satisfies(citation -> {
            assertThat(citation.n()).isEqualTo(1);
            assertThat(citation.documentId()).isEqualTo(42);
            assertThat(citation.fileName()).isEqualTo("Quy-trinh-EOD.pdf");
            assertThat(citation.page()).isEqualTo(3);
            assertThat(citation.lineStart()).isEqualTo(13);
            assertThat(citation.lineEnd()).isEqualTo(27);
            assertThat(citation.snippet()).isEqualTo(CIF.content());
        });
        assertThat(answer.citations().get(1).n()).isEqualTo(2);
        assertThat(answer.citations().get(1).page()).isNull();
        assertThat(answer.citations().get(1).fileName()).isEqualTo("Checklist.md");
    }

    @Test
    void citationsComeFromTheChunksNotFromTheModelText() {
        when(chunks.search(any(), anyInt())).thenReturn(List.of(CIF, GL));
        when(ollama.chat(anyList(), any())).thenReturn("Trả lời không có dấu nguồn nào, hoặc [9] sai số.");

        assertThat(service.answer("Hỏi").citations()).extracting(c -> c.n()).containsExactly(1, 2);
    }

    @Test
    void thePromptContainsOnlyTheRetrievedChunksAndTheQuestion() {
        when(chunks.search(any(), anyInt())).thenReturn(List.of(CIF, WEAK, GL));
        when(ollama.chat(anyList(), any())).thenReturn("ok");

        service.answer("  CIF là gì?  ");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(ollama).chat(captor.capture(), any());
        List<Message> messages = captor.getValue();
        assertThat(messages).extracting(Message::role).containsExactly("system", "user");
        assertThat(messages.get(0).content()).contains("Chỉ dùng các đoạn trong phần NGỮ CẢNH")
            .contains("Không tìm thấy thông tin này trong tài liệu.");
        String user = messages.get(1).content();
        assertThat(user).startsWith("NGỮ CẢNH:\n[1] (Quy-trinh-EOD.pdf, trang 3, dòng 13–27)\n" + CIF.content())
            .contains("[2] (Checklist.md, dòng 5–9)\n" + GL.content())
            .endsWith("CÂU HỎI: CIF là gì?")
            .doesNotContain(WEAK.content()).doesNotContain("[3]");
    }

    @Test
    void ollamaDownIsAiUnavailableWhileEmbeddingAndWhileAnswering() {
        when(ollama.embed(anyList())).thenThrow(new OllamaUnavailableException("down", null));
        assertThatThrownBy(() -> service.answer("Hỏi")).isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getResponseCode()).isEqualTo(KnowledgeResponseCode.AI_UNAVAILABLE);

        doReturn(List.of(new float[1024])).when(ollama).embed(anyList());
        when(chunks.search(any(), anyInt())).thenReturn(List.of(CIF));
        when(ollama.chat(anyList(), any())).thenThrow(new OllamaUnavailableException("down", null));
        assertThatThrownBy(() -> service.answer("Hỏi")).isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getResponseCode()).isEqualTo(KnowledgeResponseCode.AI_UNAVAILABLE);
    }

    @Test
    void aQuestionMustHaveOneToThreeHundredCharactersAfterTrimming() {
        assertThatThrownBy(() -> service.retrieve("   ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.retrieve("x".repeat(301))).isInstanceOf(BusinessException.class);
        assertThat(service.retrieve("x".repeat(300)).question()).hasSize(300);
        assertThat(service.retrieve("  hỏi  ").question()).isEqualTo("hỏi");
    }

    @Test
    void aLongChunkBecomesA280CharacterSnippetCutAtAWordBoundary() {
        String content = ("từ ").repeat(200);
        when(chunks.search(any(), anyInt())).thenReturn(List.of(
            new ChunkHit(1, 1, "a.md", null, 1, 2, content, 0.9)));
        when(ollama.chat(anyList(), any())).thenReturn("ok");

        String snippet = service.answer("Hỏi").citations().get(0).snippet();

        assertThat(snippet).endsWith("từ…").hasSizeLessThanOrEqualTo(281);
        assertThat(content).startsWith(snippet.substring(0, snippet.length() - 1));
    }

    @Test
    void streamEmitsRetrievedThenDeltasThenDone() throws IOException {
        when(chunks.search(any(), anyInt())).thenReturn(List.of(CIF, GL));
        streamDeltas("CIF là ", "mã khách hàng [1].");

        List<JsonNode> events = run(service.stream("CIF là gì?"));

        assertThat(events).extracting(e -> e.path("type").asText())
            .containsExactly("retrieved", "delta", "delta", "done");
        assertThat(events.get(0).path("count").asInt()).isEqualTo(2);
        assertThat(events.get(1).path("text").asText()).isEqualTo("CIF là ");
        assertThat(events.get(2).path("text").asText()).isEqualTo("mã khách hàng [1].");
        assertThat(events.get(3).path("found").asBoolean()).isTrue();
        assertThat(events.get(3).path("citations")).hasSize(2);
        assertThat(events.get(3).path("citations").get(0).path("lineStart").asInt()).isEqualTo(13);
    }

    @Test
    void streamWithNothingRetrievedSendsTheFixedSentenceWithoutCallingTheModel() throws IOException {
        when(chunks.search(any(), anyInt())).thenReturn(List.of(WEAK));

        List<JsonNode> events = run(service.stream("Hỏi"));

        assertThat(events).extracting(e -> e.path("type").asText()).containsExactly("retrieved", "delta", "done");
        assertThat(events.get(0).path("count").asInt()).isZero();
        assertThat(events.get(1).path("text").asText()).isEqualTo(NotFoundAnswer.TEXT);
        assertThat(events.get(2).path("found").asBoolean()).isFalse();
        assertThat(events.get(2).path("citations")).isEmpty();
        verify(ollama, never()).chatStream(anyList(), any(), any());
    }

    @Test
    void streamWithdrawsCitationsWhenTheModelSaysNothingWasFound() throws IOException {
        when(chunks.search(any(), anyInt())).thenReturn(List.of(CIF));
        streamDeltas("Không tìm thấy thông tin ", "này trong tài liệu.");

        List<JsonNode> events = run(service.stream("Hỏi"));

        JsonNode done = events.get(events.size() - 1);
        assertThat(done.path("type").asText()).isEqualTo("done");
        assertThat(done.path("found").asBoolean()).isFalse();
        assertThat(done.path("citations")).isEmpty();
    }

    @Test
    void streamEndsWithAnErrorEventWhenOllamaFailsMidAnswer() throws IOException {
        when(chunks.search(any(), anyInt())).thenReturn(List.of(CIF));
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(2);
            onDelta.accept("CIF là ");
            throw new OllamaException("stream broke");
        }).when(ollama).chatStream(anyList(), any(), any());

        List<JsonNode> events = run(service.stream("Hỏi"));

        assertThat(events).extracting(e -> e.path("type").asText()).containsExactly("retrieved", "delta", "error");
        assertThat(events.get(2).path("messageCode").asText()).isEqualTo("KNOWLEDGE-AI_UNAVAILABLE");
        assertThat(events.get(2).path("message").asText()).isNotBlank();
    }

    @Test
    void streamRefusesBeforeStartingWhenTheQuestionIsInvalidOrAiIsDown() {
        assertThatThrownBy(() -> service.stream("")).isInstanceOf(BusinessException.class);

        doThrow(new OllamaUnavailableException("down", null)).when(ollama).embed(anyList());
        assertThatThrownBy(() -> service.stream("Hỏi")).isInstanceOf(BusinessException.class);
    }

    private void streamDeltas(String... deltas) {
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(2);
            Arrays.stream(deltas).forEach(onDelta);
            return null;
        }).when(ollama).chatStream(anyList(), any(), any());
    }

    private List<JsonNode> run(StreamingResponseBody body)
        throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        body.writeTo(bytes);
        return bytes.toString(StandardCharsets.UTF_8).lines().map(line -> {
            try {
                return json.readTree(line);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }).toList();
    }
}
