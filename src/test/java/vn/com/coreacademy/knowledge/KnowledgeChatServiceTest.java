package vn.com.coreacademy.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.assistant.MutableClock;
import vn.com.coreacademy.assistant.InMemoryConversations;
import vn.com.coreacademy.assistant.LogCapture;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.conversation.TurnRecord;
import vn.com.coreacademy.assistant.config.AssistantLimits;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.metrics.AssistantMetrics;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;
import vn.com.coreacademy.knowledge.ai.OllamaException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.dto.ChatViews.ChatAnswerView;
import vn.com.coreacademy.knowledge.exception.KnowledgeResponseCode;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentVisibility;
import vn.com.coreacademy.knowledge.service.KnowledgeAccess;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.service.ChatPromptBuilder;
import vn.com.coreacademy.knowledge.service.KnowledgeChatService;
import vn.com.coreacademy.knowledge.service.NotFoundAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatServiceTest {
    private static final String USER = "000004";
    private static final ChunkHit CIF = new ChunkHit(1, 42, "Quy-trinh-EOD.pdf", 3, 13, 27,
        "CIF (Customer Information File) là mã khách hàng duy nhất.", 0.82);
    private static final ChunkHit GL = new ChunkHit(2, 43, "Checklist.md", null, 5, 9,
        "Đối chiếu số dư GL trước khi chạy EOD.", 0.55);
    // The same chunk from a second upload of the file: other ids, same text up to whitespace.
    private static final ChunkHit CIF_COPY = new ChunkHit(4, 45, "Quy-trinh-EOD (1).pdf", 3, 13, 27,
        "CIF  (Customer Information File) là mã khách hàng duy nhất.\n", 0.81);
    private static final ChunkHit WEAK = new ChunkHit(3, 44, "Khac.docx", null, 1, 2, "Nội dung không liên quan.",
        0.12);

    private final OllamaClient ollama = mock(OllamaClient.class);
    private final DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
    private final KnowledgeAccess access = mock(KnowledgeAccess.class);
    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private TurnLimiter limiter;
    private KnowledgeChatService service;

    private final InMemoryConversations conversations = new InMemoryConversations();

    @BeforeEach
    void setUp() {
        KnowledgeProperties properties = new KnowledgeProperties(Path.of("unused"), DataSize.ofMegabytes(20),
            new KnowledgeProperties.Worker(Duration.ofSeconds(2), 1, 3),
            new KnowledgeProperties.Chunk(600, 800, 80), new KnowledgeProperties.Retrieval(5, 0.35),
            new KnowledgeProperties.Ollama("http://localhost:11434", "chat", "embed", Duration.ofSeconds(5),
                Duration.ofSeconds(180), Duration.ofMinutes(10)));
        limiter = new TurnLimiter(new AssistantProperties(4, 20, Duration.ofMinutes(10), 4), new AssistantLimits(2),
            clock);
        service = new KnowledgeChatService(ollama, chunks, new ChatPromptBuilder(), properties, json, limiter,
            conversations, new AssistantMetrics(meters), vn.com.coreacademy.assistant.config.AssistantModels.defaults(),
            new vn.com.coreacademy.assistant.skill.SkillCatalog(), access,
            vn.com.coreacademy.assistant.config.AssistantBudget.defaults());
        when(access.visibility(any())).thenReturn(new DocumentVisibility(USER, java.util.Set.of()));
        when(ollama.embed(anyList())).thenReturn(List.of(new float[1024]));
        signedInAs(USER);
    }

    @AfterEach
    void signedOut() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(String userId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(userId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    void aSecondStreamOfTheSameUserEndsBusyWithoutCallingTheModel() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF));
        StreamingResponseBody body = service.stream("CIF là gì?");
        TurnLimiter.Turn running = ((TurnLimiter.Admission.Started) limiter.admit(USER)).turn();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"messageCode\":\"ASSISTANT-BUSY\"")
            .doesNotContain("retrieved");
        verify(ollama, never()).chatStream(anyList(), any(), any(), any());
        running.close();
    }

    @Test
    void theStreamAfterTheHourlyLimitEndsRateLimitedWithTheWait() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of());
        for (int i = 0; i < 2; i++) {
            service.stream("Hỏi").writeTo(new ByteArrayOutputStream());
            clock.advance(Duration.ofMinutes(5));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.stream("Hỏi").writeTo(out);

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"messageCode\":\"ASSISTANT-RATE_LIMITED\"")
            .contains("thử lại sau 50 phút");
    }

    @Test
    void theNonStreamChatIsRefusedWhileTheUsersOtherTurnRunsAndAfterTheHourlyLimit() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of());
        TurnLimiter.Turn running = ((TurnLimiter.Admission.Started) limiter.admit(USER)).turn();

        assertThatThrownBy(() -> service.answer("Hỏi")).isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getResponseCode().getCode()).isEqualTo("ASSISTANT-BUSY");
        running.close();

        // The turn held above was the hour's first question; this is the second and last.
        service.answer("Hỏi");
        assertThatThrownBy(() -> service.answer("Hỏi")).isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getResponseCode().getCode())
            .isEqualTo("ASSISTANT-RATE_LIMITED");
    }

    @Test
    void nothingAboveTheThresholdIsNotFoundAndTheModelIsNeverCalled() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(WEAK));

        ChatAnswerView answer = service.answer("Câu hỏi không có trong tài liệu?");

        assertThat(answer.found()).isFalse();
        assertThat(answer.answer()).isEqualTo("Không tìm thấy thông tin này trong tài liệu.");
        assertThat(answer.citations()).isEmpty();
        verify(ollama, never()).chat(anyList(), any());
        verify(ollama, never()).chatStream(anyList(), any(), any(), any());
    }

    @Test
    void whenTheModelSaysNothingWasFoundThereAreNoCitations() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, GL));
        when(ollama.chat(anyList(), any())).thenReturn("  \"Không tìm thấy thông tin này trong tài liệu.\"  ");

        ChatAnswerView answer = service.answer("Hỏi gì đó");

        assertThat(answer.found()).isFalse();
        assertThat(answer.answer()).isEqualTo(NotFoundAnswer.TEXT);
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void aNormalAnswerCitesOnlyTheChunksItMarksWithTheirPageAndLines() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, WEAK, GL));
        when(ollama.chat(anyList(), any())).thenReturn("CIF là mã khách hàng duy nhất [1].");

        ChatAnswerView answer = service.answer("CIF là gì?");

        assertThat(answer.found()).isTrue();
        assertThat(answer.answer()).isEqualTo("CIF là mã khách hàng duy nhất [1].");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().get(0)).satisfies(citation -> {
            assertThat(citation.n()).isEqualTo(1);
            assertThat(citation.documentId()).isEqualTo(42);
            assertThat(citation.fileName()).isEqualTo("Quy-trinh-EOD.pdf");
            assertThat(citation.page()).isEqualTo(3);
            assertThat(citation.lineStart()).isEqualTo(13);
            assertThat(citation.lineEnd()).isEqualTo(27);
            assertThat(citation.snippet()).isEqualTo(CIF.content());
        });
    }

    @Test
    void aCitationKeepsTheNumberTheModelSawEvenWhenEarlierChunksAreLeftOut() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, GL));
        when(ollama.chat(anyList(), any())).thenReturn("Đối chiếu GL trước EOD [2].");

        ChatAnswerView answer = service.answer("Trước EOD cần làm gì?");

        assertThat(answer.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.n()).isEqualTo(2);
            assertThat(citation.fileName()).isEqualTo("Checklist.md");
            assertThat(citation.page()).isNull();
        });
    }

    @Test
    void everyMarkerFormNamesItsChunksAndEachChunkIsCitedOnce() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, GL));
        when(ollama.chat(anyList(), any())).thenReturn("Ý một [2][1]. Ý hai [1, 2]. Lại [2].");

        assertThat(service.answer("Hỏi").citations()).extracting(c -> c.n()).containsExactly(1, 2);
    }

    @Test
    void withoutAUsableMarkerEveryRetrievedChunkIsASource() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, GL));
        when(ollama.chat(anyList(), any())).thenReturn("Không có dấu nguồn, chỉ [9] và [12345678901].");

        assertThat(service.answer("Hỏi").citations()).extracting(c -> c.n()).containsExactly(1, 2);
    }

    @Test
    void outOfRangeMarkersAreIgnoredBesideAValidOne() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, GL));
        when(ollama.chat(anyList(), any())).thenReturn("CIF [1][7].");

        assertThat(service.answer("Hỏi").citations()).extracting(c -> c.n()).containsExactly(1);
    }

    @Test
    void aChunkStoredTwiceReachesThePromptAndTheSourcesOnce() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, CIF_COPY, GL));
        when(ollama.chat(anyList(), any())).thenReturn("CIF [1]. GL [2].");

        ChatAnswerView answer = service.answer("CIF và GL?");

        verify(chunks).search(any(), eq(15), any());
        assertThat(answer.citations()).extracting(c -> c.n(), c -> c.documentId())
            .containsExactly(tuple(1, 42L), tuple(2, 43L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(ollama).chat(captor.capture(), any());
        String prompt = captor.getValue().get(1).content();
        assertThat(prompt).contains("[1] (Quy-trinh-EOD.pdf").contains("[2] (Checklist.md")
            .doesNotContain("Quy-trinh-EOD (1).pdf").doesNotContain("[3]");
    }

    @Test
    void distinctChunksStopAtTopK() {
        List<ChunkHit> many = IntStream.range(0, 8)
            .mapToObj(i -> new ChunkHit(10 + i, 50 + i, "f" + i + ".md", null, 1, 1, "Đoạn " + i, 0.9 - i * 0.01))
            .toList();
        when(chunks.search(any(), anyInt(), any())).thenReturn(many);
        when(ollama.chat(anyList(), any())).thenReturn("Không có dấu nguồn.");

        assertThat(service.answer("Hỏi").citations()).extracting(c -> c.n()).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void thePromptContainsOnlyTheRetrievedChunksAndTheQuestion() {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, WEAK, GL));
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
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF));
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
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(
            new ChunkHit(1, 1, "a.md", null, 1, 2, content, 0.9)));
        when(ollama.chat(anyList(), any())).thenReturn("ok");

        String snippet = service.answer("Hỏi").citations().get(0).snippet();

        assertThat(snippet).endsWith("từ…").hasSizeLessThanOrEqualTo(281);
        assertThat(content).startsWith(snippet.substring(0, snippet.length() - 1));
    }

    @Test
    void streamEmitsRetrievedThenDeltasThenDone() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, GL));
        streamDeltas("CIF là ", "mã khách hàng [1].");

        List<JsonNode> events = run(service.stream("CIF là gì?"));

        assertThat(events).extracting(e -> e.path("type").asText())
            .containsExactly("retrieved", "delta", "delta", "done");
        assertThat(events.get(0).path("count").asInt()).isEqualTo(2);
        assertThat(events.get(1).path("text").asText()).isEqualTo("CIF là ");
        assertThat(events.get(2).path("text").asText()).isEqualTo("mã khách hàng [1].");
        assertThat(events.get(3).path("found").asBoolean()).isTrue();
        assertThat(events.get(3).path("answer").asText()).isEqualTo("CIF là mã khách hàng [1].");
        assertThat(events.get(3).path("citations")).hasSize(1);
        assertThat(events.get(3).path("citations").get(0).path("lineStart").asInt()).isEqualTo(13);
    }

    @Test
    void theFirstLineNamesTheNewConversationAndTheTurnIsStored() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, GL));
        streamDeltas("CIF là ", "mã khách hàng [1].");

        List<JsonNode> events = runRaw(service.stream("CIF là gì?"));

        assertThat(events.get(0).path("type").asText()).isEqualTo("conversation");
        assertThat(events.get(0).path("conversationId").asLong()).isPositive();
        assertThat(conversations.recorded).hasSize(1);
        TurnRecord stored = conversations.recorded.get(0);
        assertThat(stored.surface()).isEqualTo(TurnRecord.KNOWLEDGE);
        assertThat(stored.question()).isEqualTo("CIF là gì?");
        assertThat(stored.answer()).isEqualTo("CIF là mã khách hàng [1].");
        assertThat(stored.citations()).hasSize(1);
        assertThat(stored.status()).isEqualTo(TurnRecord.Status.DONE);
    }

    @Test
    void suggestionsLeaveTheAnswerAndTheStreamAndTheTurnStoresTheSkillAndPromptVersion() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF));
        streamDeltas("CIF là mã khách hàng [1].\n@@GOI", "_Y@@\n- CIF cấp thế nào?\n- Ai quản lý CIF?");

        List<JsonNode> events = runRaw(service.stream("CIF là gì?"));

        String streamed = events.stream().filter(e -> e.path("type").asText().equals("delta"))
            .map(e -> e.path("text").asText()).reduce("", String::concat);
        assertThat(streamed).doesNotContain("GOI_Y").doesNotContain("cấp thế nào");
        JsonNode done = events.get(events.size() - 1);
        assertThat(done.path("answer").asText()).isEqualTo("CIF là mã khách hàng [1].");
        assertThat(done.path("suggestions")).extracting(JsonNode::asText)
            .containsExactly("CIF cấp thế nào?", "Ai quản lý CIF?");
        assertThat(conversations.lastRecord().skill()).isEqualTo("qa_tai_lieu");
        assertThat(conversations.lastRecord().promptVersion()).isEqualTo(new SkillCatalog().version());
        assertThat(conversations.lastRecord().answer()).doesNotContain("GOI_Y");
    }

    @Test
    void aFollowUpGivesTheModelTheEarlierTurnsButRetrievesWithTheNewQuestionAlone() throws IOException {
        long id = conversations.seed(USER, "CIF là gì?", "CIF là mã khách hàng [1].");
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF));
        streamDeltas("Có [1].");

        List<JsonNode> events = runRaw(service.stream("Còn ngoại lệ?", id));

        assertThat(events.get(0).path("conversationId").asLong()).isEqualTo(id);
        ArgumentCaptor<List<OllamaClient.Message>> sent = ArgumentCaptor.captor();
        verify(ollama).chatStream(sent.capture(), any(), any(), any());
        assertThat(sent.getValue()).extracting(OllamaClient.Message::role)
            .containsExactly("system", "user", "assistant", "user");
        assertThat(sent.getValue().get(1).content()).isEqualTo("CIF là gì?");
        assertThat(sent.getValue().get(2).content()).isEqualTo("CIF là mã khách hàng [1].");
        assertThat(sent.getValue().get(3).content()).contains("NGỮ CẢNH:").endsWith("CÂU HỎI: Còn ngoại lệ?");
        verify(ollama).embed(List.of("Còn ngoại lệ?"));
    }

    @Test
    void neitherTheQuestionNorTheAnswerReachesTheLogs() throws IOException {
        try (LogCapture logs = new LogCapture()) {
            when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF));
            streamDeltas("Đáp án riêng tư 24680 [1].");
            run(service.stream("Câu hỏi riêng tư 13579"));

            assertThat(logs.lines()).isNotEmpty();
            assertThat(logs.lines()).allSatisfy(line -> assertThat(line)
                .doesNotContain("riêng tư").doesNotContain("13579").doesNotContain("24680"));
        }
    }

    @Test
    void anotherUsersConversationIdIsRefusedBeforeTheStreamAsNotFound() {
        long theirs = conversations.seed("000005", "Hỏi", "Đáp");

        assertThatThrownBy(() -> service.stream("Hỏi", theirs)).isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getResponseCode().getCode())
            .isEqualTo("ASSISTANT-CONVERSATION_NOT_FOUND");
        verify(ollama, never()).embed(anyList());
    }

    @Test
    void streamWithNothingRetrievedSendsTheFixedSentenceWithoutCallingTheModel() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(WEAK));

        List<JsonNode> events = run(service.stream("Hỏi"));

        assertThat(events).extracting(e -> e.path("type").asText()).containsExactly("retrieved", "delta", "done");
        assertThat(events.get(0).path("count").asInt()).isZero();
        assertThat(events.get(1).path("text").asText()).isEqualTo(NotFoundAnswer.TEXT);
        assertThat(events.get(2).path("found").asBoolean()).isFalse();
        assertThat(events.get(2).path("answer").asText()).isEqualTo(NotFoundAnswer.TEXT);
        assertThat(events.get(2).path("citations")).isEmpty();
        verify(ollama, never()).chatStream(anyList(), any(), any(), any());
    }

    @Test
    void aStreamedTurnAndANotFoundAnswerAreCountedWithoutAnyUserText() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(WEAK));

        run(service.stream("Hỏi chuyện riêng tư"));

        assertThat(meters.get("assistant.turn.duration").tag("surface", "knowledge").tag("status", "done").timer()
            .count()).isEqualTo(1);
        assertThat(meters.get("assistant.answers.not_found").tag("surface", "knowledge").counter().count())
            .isEqualTo(1);
        assertThat(meters.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
            .map(io.micrometer.core.instrument.Tag::getValue)).allSatisfy(value -> assertThat(value)
            .doesNotContain("riêng tư").doesNotContain(USER));
    }

    @Test
    void streamWithdrawsCitationsWhenTheModelSaysNothingWasFound() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF));
        streamDeltas("Không tìm thấy thông tin ", "này trong tài liệu.");

        List<JsonNode> events = run(service.stream("Hỏi"));

        JsonNode done = events.get(events.size() - 1);
        assertThat(done.path("type").asText()).isEqualTo("done");
        assertThat(done.path("found").asBoolean()).isFalse();
        assertThat(done.path("citations")).isEmpty();
    }

    @Test
    void streamReplacesTheTextWithTheFixedSentenceWhenTheModelAddsSourceMarkers() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF, GL));
        streamDeltas("Không tìm thấy thông tin này trong tài liệu.", "[1][2]");

        List<JsonNode> events = run(service.stream("Hỏi"));

        JsonNode done = events.get(events.size() - 1);
        assertThat(done.path("found").asBoolean()).isFalse();
        assertThat(done.path("answer").asText()).isEqualTo(NotFoundAnswer.TEXT);
        assertThat(done.path("citations")).isEmpty();
    }

    @Test
    void streamEndsWithAnErrorEventWhenOllamaFailsMidAnswer() throws IOException {
        when(chunks.search(any(), anyInt(), any())).thenReturn(List.of(CIF));
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(2);
            onDelta.accept("CIF là ");
            throw new OllamaException("stream broke");
        }).when(ollama).chatStream(anyList(), any(), any(), any());

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
        }).when(ollama).chatStream(anyList(), any(), any(), any());
    }

    /** The events after the opening {@code conversation} line, which has its own tests. */
    private List<JsonNode> run(StreamingResponseBody body) throws IOException {
        List<JsonNode> all = runRaw(body);
        return all.get(0).path("type").asText().equals("conversation") ? all.subList(1, all.size()) : all;
    }

    private List<JsonNode> runRaw(StreamingResponseBody body) throws IOException {
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
