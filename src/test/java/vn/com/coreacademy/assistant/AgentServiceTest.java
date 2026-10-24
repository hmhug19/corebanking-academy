package vn.com.coreacademy.assistant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.assistant.action.PendingAction;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.agent.AgentPrompt;
import vn.com.coreacademy.assistant.agent.AgentService;
import vn.com.coreacademy.assistant.agent.ToolRegistry;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.dto.AgentMessage;
import vn.com.coreacademy.assistant.dto.AgentRequest;
import vn.com.coreacademy.assistant.dto.PendingActionView.Change;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.assistant.tool.AgentTool;
import vn.com.coreacademy.assistant.tool.FinishedAnswer;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.dto.FunctionInfo;
import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.service.AuthService;
import vn.com.coreacademy.knowledge.ai.ChatMessage;
import vn.com.coreacademy.knowledge.ai.ChatTurn;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.ai.ToolCall;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;
import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentServiceTest {
    private static final String STEP_CAP_SENTENCE =
        "Mình chưa xử lý được yêu cầu này. Bạn thử diễn đạt lại ngắn gọn hơn nhé.";

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final AuthService auth = mock(AuthService.class);
    private final List<AgentTool> tools = new ArrayList<>();
    private final List<AgentContext> contexts = new ArrayList<>();
    private final AssistantProperties properties = new AssistantProperties(4, 20, Duration.ofMinutes(10), 4);
    private final TurnLimiter limiter = new TurnLimiter(properties);

    @BeforeEach
    void setUp() {
        when(ollama.isAvailable()).thenReturn(true);
        UserInfo profile = new UserInfo("000004", "hocvien", "Học viên Demo", "hv@example.test", "active", "LOCAL",
            false, 1L, "ORG", "Đơn vị", "UNIT", null, null, null, "Chuyên viên", null, null, null, null, null, null,
            null, null, null, null, null, null);
        FunctionInfo screen = new FunctionInfo(9L, "USER_MANAGEMENT", "Quản lý người dùng", "/admin/users", null, 90);
        when(auth.me()).thenReturn(new MeResponse(profile, List.of(), List.of("USER_MANAGEMENT", "AI_ASSISTANT"),
            List.of(), List.of(screen), Map.of()));
    }

    /** Built per call: the registry reads the tools once, so the test's tools must exist before it is created. */
    private AgentService service() {
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentTool> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> tools.stream());
        return new AgentService(ollama, auth, new ToolRegistry(provider), properties, limiter, json);
    }

    /** A tool that records the context it saw and delegates to {@code behaviour}. */
    private AgentTool tool(String name, boolean available, BiFunction<JsonNode, TurnState, ToolOutcome> behaviour) {
        return tool(name, available, false, false, behaviour);
    }

    private AgentTool tool(String name, boolean available, boolean readsSharedContent, boolean actsForUser,
                           BiFunction<JsonNode, TurnState, ToolOutcome> behaviour) {
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean availableTo(AgentContext context) {
                return available;
            }

            @Override
            public boolean readsSharedContent() {
                return readsSharedContent;
            }

            @Override
            public boolean actsForUser() {
                return actsForUser;
            }

            @Override
            public ToolDefinition definition(AgentContext context) {
                return new ToolDefinition(name, "description of " + name, json.createObjectNode().put("type", "object"));
            }

            @Override
            public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
                contexts.add(context);
                return behaviour.apply(arguments, turn);
            }
        };
        tools.add(tool);
        return tool;
    }

    private static ToolCall call(String name) {
        return ToolCall.of(name, new ObjectMapper().createObjectNode().put("q", "x"));
    }

    /** The next model call streams {@code deltas} and answers with them and {@code calls}. */
    private void modelAnswers(List<String> deltas, List<ToolCall> calls, ChatTurn... later) {
        List<ChatTurn> turns = new ArrayList<>();
        turns.add(new ChatTurn(String.join("", deltas), calls));
        turns.addAll(List.of(later));
        List<List<String>> deltaPerCall = new ArrayList<>();
        deltaPerCall.add(deltas);
        for (ChatTurn turn : later) {
            deltaPerCall.add(turn.content().isEmpty() ? List.of() : List.of(turn.content()));
        }
        int[] call = {0};
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(3);
            int index = Math.min(call[0]++, turns.size() - 1);
            deltaPerCall.get(index).forEach(onDelta);
            return turns.get(index);
        }).when(ollama).chatWithTools(anyList(), anyList(), any(), any());
    }

    private List<JsonNode> events(StreamingResponseBody body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        List<JsonNode> events = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                events.add(json.readTree(line));
            }
        }
        return events;
    }

    private List<String> types(List<JsonNode> events) {
        return events.stream().map(event -> event.path("type").asText()).toList();
    }

    private StreamingResponseBody ask(String question) {
        return service().stream(List.of(ChatMessage.user(question)));
    }

    @Test
    void aPlainAnswerStreamsDeltasThenDoneWithTheFullTextAndNoCitations() throws IOException {
        modelAnswers(List.of("Xin ", "chào"), List.of());

        List<JsonNode> events = events(ask("Xin chào"));

        assertThat(types(events)).containsExactly("delta", "delta", "done");
        assertThat(events.get(0).path("text").asText()).isEqualTo("Xin ");
        assertThat(events.get(2).path("answer").asText()).isEqualTo("Xin chào");
        assertThat(events.get(2).path("citations").size()).isZero();
        verify(ollama, times(1)).chatWithTools(anyList(), anyList(), any(), any());
    }

    @Test
    void aUserWithoutTheAssistantFunctionIsRefusedBeforeAnythingRuns() {
        MeResponse me = auth.me();
        when(auth.me()).thenReturn(new MeResponse(me.user(), me.roles(), List.of("USER_MANAGEMENT"), me.roleGrants(),
            me.functionDetails(), me.functionScopes()));

        assertThatThrownBy(() -> ask("Xin chào")).isInstanceOf(AccessDeniedException.class);
        verify(ollama, never()).isAvailable();
    }

    @Test
    void atMostThreeToolCallsOfOneStepRunAndOnlyThoseGoBackToTheModel() throws IOException {
        tool("search_documents", true, (arguments, turn) -> new ToolOutcome.Result("kết quả"));
        modelAnswers(List.of(), Collections.nCopies(5, call("search_documents")), new ChatTurn("Xong.", List.of()));

        List<JsonNode> events = events(ask("CIF là gì?"));

        assertThat(types(events)).containsExactly("tool", "tool", "tool", "delta", "done");
        assertThat(contexts).hasSize(AgentService.MAX_CALLS_PER_STEP);
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.captor();
        verify(ollama, times(2)).chatWithTools(messages.capture(), anyList(), any(), any());
        List<ChatMessage> second = messages.getAllValues().get(1);
        assertThat(second.get(2).toolCalls()).hasSize(AgentService.MAX_CALLS_PER_STEP);
        assertThat(second).extracting(ChatMessage::role).containsExactly("system", "user", "assistant", "tool", "tool",
            "tool");
    }

    @Test
    void theOldestTurnsAreDroppedSoTheHistoryStaysWithinItsCharacterBudget() throws IOException {
        modelAnswers(List.of("ok"), List.of());
        List<AgentMessage> request = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            request.add(new AgentMessage("user", "câu " + i));
            request.add(new AgentMessage("assistant", "x".repeat(4000)));
        }
        request.add(new AgentMessage("user", "câu cuối"));

        events(service().stream(new AgentRequest(request)));

        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.captor();
        verify(ollama).chatWithTools(messages.capture(), anyList(), any(), any());
        List<ChatMessage> history = messages.getValue().subList(1, messages.getValue().size());
        assertThat(history.stream().mapToInt(message -> message.content().length()).sum())
            .isLessThanOrEqualTo(AgentService.MAX_HISTORY_CHARS);
        assertThat(history.get(0).role()).isEqualTo("user");
        assertThat(history.get(0).content()).isEqualTo("câu 6");
        assertThat(history.get(history.size() - 1).content()).isEqualTo("câu cuối");
    }

    @Test
    void aSecondTurnOfTheSameUserWhileOneIsRunningEndsBusyWithoutCallingTheModel() throws IOException {
        limiter.tryStart("000004").orElseThrow();

        List<JsonNode> events = events(ask("Xin chào"));

        assertThat(types(events)).containsExactly("error");
        assertThat(events.get(0).path("messageCode").asText()).isEqualTo("ASSISTANT-BUSY");
        verify(ollama, never()).chatWithTools(anyList(), anyList(), any(), any());
    }

    @Test
    void aTurnFreesItsPlaceWhenItEndsEvenOnAFailure() throws IOException {
        when(ollama.chatWithTools(anyList(), anyList(), any(), any())).thenThrow(new IllegalStateException("boom"));

        events(ask("Xin chào"));

        assertThat(limiter.tryStart("000004")).isPresent();
    }

    @Test
    void aBlankFinalAnswerIsReplacedWithTheFallbackSentence() throws IOException {
        modelAnswers(List.of(), List.of());

        List<JsonNode> events = events(ask("Xin chào"));

        assertThat(types(events)).containsExactly("done");
        assertThat(events.get(0).path("answer").asText()).isEqualTo(AgentService.EMPTY_ANSWER);
    }

    @Test
    void aBlankAnswerAfterASearchWithSourcesIsTheFallbackWithNoCitations() throws IOException {
        tool("search_documents", true, true, false, (arguments, turn) -> {
            turn.finishWith(answer -> new FinishedAnswer(answer, List.of(
                new CitationView(1, 42L, "a.pdf", 1, 1, 2, "x"))));
            return new ToolOutcome.Result("[1] tài liệu");
        });
        modelAnswers(List.of(), List.of(call("search_documents")), new ChatTurn("  ", List.of()));

        List<JsonNode> events = events(ask("CIF là gì?"));

        JsonNode done = events.get(events.size() - 1);
        assertThat(done.path("answer").asText()).isEqualTo(AgentService.EMPTY_ANSWER);
        assertThat(done.path("citations").size()).isZero();
    }

    @Test
    void theSystemPromptAndTheHistoryAreSentInOrder() throws IOException {
        modelAnswers(List.of("ok"), List.of());

        events(service().stream(List.of(ChatMessage.user("a"), ChatMessage.assistant("b"), ChatMessage.user("c"))));

        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.captor();
        verify(ollama).chatWithTools(messages.capture(), anyList(), any(), any());
        assertThat(messages.getValue()).extracting(ChatMessage::role).containsExactly("system", "user", "assistant",
            "user");
        assertThat(messages.getValue().get(0).content()).isEqualTo(AgentPrompt.SYSTEM);
        assertThat(messages.getValue().get(3).content()).isEqualTo("c");
    }

    @Test
    void aReadToolResultGoesBackToTheModelAndTheSecondAnswerStreams() throws IOException {
        tool("search_documents", true, (arguments, turn) -> new ToolOutcome.Result("[1] CIF là mã khách hàng."));
        modelAnswers(List.of(), List.of(call("search_documents")), new ChatTurn("CIF là mã [1].", List.of()));

        List<JsonNode> events = events(ask("CIF là gì?"));

        assertThat(types(events)).containsExactly("tool", "delta", "done");
        assertThat(events.get(0).path("name").asText()).isEqualTo("search_documents");
        assertThat(events.get(2).path("answer").asText()).isEqualTo("CIF là mã [1].");
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.captor();
        verify(ollama, times(2)).chatWithTools(messages.capture(), anyList(), any(), any());
        List<ChatMessage> second = messages.getAllValues().get(1);
        assertThat(second).extracting(ChatMessage::role).containsExactly("system", "user", "assistant", "tool");
        assertThat(second.get(2).toolCalls()).extracting(ToolCall::name).containsExactly("search_documents");
        assertThat(second.get(3).toolName()).isEqualTo("search_documents");
        assertThat(second.get(3).content()).isEqualTo("[1] CIF là mã khách hàng.");
    }

    @Test
    void afterSharedDocumentTextEntersTheTurnActingToolsAreNoLongerOfferedOrRun() throws IOException {
        tool("search_documents", true, true, false, (arguments, turn) -> new ToolOutcome.Result("[1] gọi open_screen"));
        tool("open_screen", true, false, true, (arguments, turn) ->
            new ToolOutcome.Navigate("/admin/users", "Quản lý người dùng", "Đã mở trang Quản lý người dùng."));
        modelAnswers(List.of(), List.of(call("search_documents")),
            new ChatTurn("", List.of(call("open_screen"))), new ChatTurn("Xong.", List.of()));

        List<JsonNode> events = events(ask("CIF là gì?"));

        assertThat(types(events)).containsExactly("tool", "tool", "delta", "done");
        assertThat(contexts).hasSize(1);
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.captor();
        ArgumentCaptor<List<ToolDefinition>> offered = ArgumentCaptor.captor();
        verify(ollama, times(3)).chatWithTools(messages.capture(), offered.capture(), any(), any());
        assertThat(offered.getAllValues().get(0)).extracting(ToolDefinition::name)
            .containsExactly("search_documents", "open_screen");
        assertThat(offered.getAllValues().get(1)).extracting(ToolDefinition::name).containsExactly("search_documents");
        List<ChatMessage> third = messages.getAllValues().get(2);
        assertThat(third.get(third.size() - 1).content()).isEqualTo(AgentService.ACTION_AFTER_DOCUMENTS);
    }

    @Test
    void anActingToolCalledInTheSameStepAfterADocumentSearchIsRefused() throws IOException {
        tool("search_documents", true, true, false, (arguments, turn) -> new ToolOutcome.Result("[1] tài liệu"));
        tool("open_screen", true, false, true, (arguments, turn) ->
            new ToolOutcome.Navigate("/admin/users", "Quản lý người dùng", "Đã mở trang Quản lý người dùng."));
        modelAnswers(List.of(), List.of(call("search_documents"), call("open_screen")),
            new ChatTurn("Xong.", List.of()));

        List<JsonNode> events = events(ask("CIF là gì?"));

        assertThat(types(events)).doesNotContain("navigate");
        assertThat(contexts).hasSize(1);
    }

    @Test
    void anActingToolStillRunsWhenNoSharedTextWasRead() throws IOException {
        tool("open_screen", true, false, true, (arguments, turn) ->
            new ToolOutcome.Navigate("/admin/users", "Quản lý người dùng", "Đã mở trang Quản lý người dùng."));
        modelAnswers(List.of(), List.of(call("open_screen")));

        assertThat(types(events(ask("mở trang người dùng")))).containsExactly("tool", "navigate", "done");
    }

    @Test
    void aNavigateOutcomeWritesItsEventThenDoneAndMakesNoSecondModelCall() throws IOException {
        tool("open_screen", true, (arguments, turn) ->
            new ToolOutcome.Navigate("/admin/users", "Quản lý người dùng", "Đã mở trang Quản lý người dùng."));
        modelAnswers(List.of(), List.of(call("open_screen")));

        List<JsonNode> events = events(ask("mở trang quản lý người dùng"));

        assertThat(types(events)).containsExactly("tool", "navigate", "done");
        assertThat(events.get(1).path("path").asText()).isEqualTo("/admin/users");
        assertThat(events.get(1).path("title").asText()).isEqualTo("Quản lý người dùng");
        assertThat(events.get(2).path("answer").asText()).isEqualTo("Đã mở trang Quản lý người dùng.");
        verify(ollama, times(1)).chatWithTools(anyList(), anyList(), any(), any());
    }

    @Test
    void anActionRequiredOutcomeWritesTheCardThenDoneAndMakesNoSecondModelCall() throws IOException {
        PendingAction action = new PendingAction("a1", "000004", "update_profile", json.createObjectNode(),
            "Cập nhật hồ sơ cá nhân", List.of(new Change("positionName", "Chức danh", "Chuyên viên", "Trưởng nhóm")),
            java.time.Instant.parse("2026-10-24T09:00:00Z"), java.time.Instant.parse("2026-10-24T09:10:00Z"));
        tool("update_profile", true, (arguments, turn) ->
            new ToolOutcome.ActionRequired(action, "Vui lòng kiểm tra và xác nhận thay đổi bên dưới."));
        modelAnswers(List.of(), List.of(call("update_profile")));

        List<JsonNode> events = events(ask("đổi chức danh"));

        assertThat(types(events)).containsExactly("tool", "action_required", "done");
        JsonNode card = events.get(1).path("action");
        assertThat(card.path("id").asText()).isEqualTo("a1");
        assertThat(card.path("tool").asText()).isEqualTo("update_profile");
        assertThat(card.path("changes").get(0).path("from").asText()).isEqualTo("Chuyên viên");
        assertThat(card.path("expiresAt").asText()).isEqualTo("2026-10-24T09:10:00Z");
        assertThat(card.has("userId")).isFalse();
        assertThat(events.get(2).path("answer").asText()).isEqualTo("Vui lòng kiểm tra và xác nhận thay đổi bên dưới.");
        verify(ollama, times(1)).chatWithTools(anyList(), anyList(), any(), any());
    }

    @Test
    void anUnknownToolGoesBackToTheModelAsText() throws IOException {
        modelAnswers(List.of(), List.of(call("delete_everything")), new ChatTurn("Mình không làm được.", List.of()));

        List<JsonNode> events = events(ask("xoá hết"));

        assertThat(types(events)).containsExactly("tool", "delta", "done");
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.captor();
        verify(ollama, times(2)).chatWithTools(messages.capture(), anyList(), any(), any());
        ChatMessage result = messages.getAllValues().get(1).get(3);
        assertThat(result.role()).isEqualTo("tool");
        assertThat(result.content()).isEqualTo("Công cụ không tồn tại.");
    }

    @Test
    void theValidationTextOfAToolGoesBackToTheModel() throws IOException {
        tool("update_profile", true, (arguments, turn) -> new ToolOutcome.Result("Email không hợp lệ."));
        modelAnswers(List.of(), List.of(call("update_profile")), new ChatTurn("Email bạn đưa chưa đúng.", List.of()));

        List<JsonNode> events = events(ask("đổi email thành abc"));

        assertThat(types(events)).containsExactly("tool", "delta", "done");
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.captor();
        verify(ollama, times(2)).chatWithTools(messages.capture(), anyList(), any(), any());
        assertThat(messages.getAllValues().get(1).get(3).content()).isEqualTo("Email không hợp lệ.");
    }

    @Test
    void argumentsThatAreNotAnObjectNeverReachTheTool() throws IOException {
        tool("search_documents", true, (arguments, turn) -> new ToolOutcome.Result("should not run"));
        modelAnswers(List.of(), List.of(ToolCall.of("search_documents", json.getNodeFactory().textNode("CIF"))),
            new ChatTurn("Bạn hỏi lại giúp mình.", List.of()));

        events(ask("CIF là gì?"));

        assertThat(contexts).isEmpty();
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.captor();
        verify(ollama, times(2)).chatWithTools(messages.capture(), anyList(), any(), any());
        assertThat(messages.getAllValues().get(1).get(3).content()).isEqualTo("Tham số của công cụ không hợp lệ.");
    }

    @Test
    void theStepCapEndsTheTurnWithTheFixedSentence() throws IOException {
        tool("search_documents", true, (arguments, turn) -> new ToolOutcome.Result("again"));
        modelAnswers(List.of(), List.of(call("search_documents")));

        List<JsonNode> events = events(ask("CIF là gì?"));

        verify(ollama, times(4)).chatWithTools(anyList(), anyList(), any(), any());
        JsonNode done = events.get(events.size() - 1);
        assertThat(done.path("type").asText()).isEqualTo("done");
        assertThat(done.path("answer").asText()).isEqualTo(STEP_CAP_SENTENCE);
        assertThat(done.path("citations").size()).isZero();
    }

    @Test
    void aToolsOnlyOfferedToItsUsersIsNeverOfferedOrRunForOthers() throws IOException {
        tool("search_documents", true, (arguments, turn) -> new ToolOutcome.Result("ok"));
        tool("admin_only", false, (arguments, turn) -> new ToolOutcome.Result("secret"));
        modelAnswers(List.of(), List.of(call("admin_only")), new ChatTurn("Không được.", List.of()));

        events(ask("làm việc quản trị"));

        ArgumentCaptor<List<ToolDefinition>> offered = ArgumentCaptor.captor();
        ArgumentCaptor<List<ChatMessage>> messages = ArgumentCaptor.captor();
        verify(ollama, times(2)).chatWithTools(messages.capture(), offered.capture(), any(), any());
        assertThat(offered.getAllValues().get(0)).extracting(ToolDefinition::name).containsExactly("search_documents");
        assertThat(messages.getAllValues().get(1).get(3).content()).isEqualTo("Công cụ không tồn tại.");
        assertThat(contexts).isEmpty();
    }

    @Test
    void theToolsReceiveTheContextBuiltFromTheSignedInUser() throws IOException {
        tool("search_documents", true, (arguments, turn) -> new ToolOutcome.Result("ok"));
        modelAnswers(List.of(), List.of(call("search_documents")), new ChatTurn("xong", List.of()));

        events(ask("CIF là gì?"));

        assertThat(contexts).hasSize(1);
        assertThat(contexts.get(0).userId()).isEqualTo("000004");
        assertThat(contexts.get(0).profile().positionName()).isEqualTo("Chuyên viên");
        assertThat(contexts.get(0).rights().functions()).extracting(FunctionInfo::code)
            .containsExactly("USER_MANAGEMENT");
    }

    @Test
    void aTurnFinisherShapesTheDoneEvent() throws IOException {
        CitationView citation = new CitationView(1, 7L, "EOD.pdf", 2, 4, 6, "snippet");
        tool("search_documents", true, (arguments, turn) -> {
            turn.finishWith(answer -> new FinishedAnswer(answer.toUpperCase(), List.of(citation)));
            return new ToolOutcome.Result("[1] text");
        });
        modelAnswers(List.of(), List.of(call("search_documents")), new ChatTurn("trả lời [1]", List.of()));

        List<JsonNode> events = events(ask("CIF là gì?"));

        JsonNode done = events.get(events.size() - 1);
        assertThat(done.path("answer").asText()).isEqualTo("TRẢ LỜI [1]");
        assertThat(done.path("citations").get(0).path("fileName").asText()).isEqualTo("EOD.pdf");
        assertThat(done.path("citations").get(0).path("lineEnd").asInt()).isEqualTo(6);
    }

    @Test
    void theFinisherSeesOnlyTheTextOfTheFinalModelCall() throws IOException {
        List<String> seen = new ArrayList<>();
        tool("search_documents", true, (arguments, turn) -> {
            turn.finishWith(answer -> {
                seen.add(answer);
                return new FinishedAnswer(answer, List.of());
            });
            return new ToolOutcome.Result("[1] text");
        });
        modelAnswers(List.of("Để mình tìm. "), List.of(call("search_documents")), new ChatTurn("Kết quả.", List.of()));

        events(ask("CIF là gì?"));

        assertThat(seen).containsExactly("Kết quả.");
    }

    @Test
    void ollamaGoingDownMidStreamEndsWithAnErrorEvent() throws IOException {
        doAnswer(invocation -> {
            Consumer<String> onDelta = invocation.getArgument(3);
            onDelta.accept("Một ");
            throw new OllamaUnavailableException("down", null);
        }).when(ollama).chatWithTools(anyList(), anyList(), any(), any());

        List<JsonNode> events = events(ask("Xin chào"));

        assertThat(types(events)).containsExactly("delta", "error");
        assertThat(events.get(1).path("messageCode").asText()).isEqualTo("ASSISTANT-AI_UNAVAILABLE");
        assertThat(events.get(1).path("message").asText()).isEqualTo(AssistantResponseCode.AI_UNAVAILABLE.getMessage());
    }

    @Test
    void anOllamaErrorEndsWithTheSameAiUnavailableEvent() throws IOException {
        doAnswer(invocation -> {
            throw new OllamaException("crashed");
        }).when(ollama).chatWithTools(anyList(), anyList(), any(), any());

        List<JsonNode> events = events(ask("Xin chào"));

        assertThat(types(events)).containsExactly("error");
        assertThat(events.get(0).path("messageCode").asText()).isEqualTo("ASSISTANT-AI_UNAVAILABLE");
    }

    @Test
    void aToolThatThrowsEndsWithAGenericInternalErrorAndNoDetails() throws IOException {
        tool("search_documents", true, (arguments, turn) -> {
            throw new IllegalStateException("secret detail about the query");
        });
        modelAnswers(List.of(), List.of(call("search_documents")));

        List<JsonNode> events = events(ask("CIF là gì?"));

        JsonNode error = events.get(events.size() - 1);
        assertThat(error.path("type").asText()).isEqualTo("error");
        assertThat(error.path("messageCode").asText()).isEqualTo("CORE-INTERNAL_ERROR");
        assertThat(error.path("message").asText()).doesNotContain("secret");
    }

    @Test
    void aToolThatReportsAiUnavailableEndsWithAnAiUnavailableEvent() throws IOException {
        tool("search_documents", true, (arguments, turn) -> {
            throw new OllamaUnavailableException("down", null);
        });
        modelAnswers(List.of(), List.of(call("search_documents")));

        List<JsonNode> events = events(ask("CIF là gì?"));

        assertThat(types(events)).containsExactly("tool", "error");
        assertThat(events.get(1).path("messageCode").asText()).isEqualTo("ASSISTANT-AI_UNAVAILABLE");
    }

    @Test
    void anUnreachableOllamaIsRefusedBeforeTheStreamStarts() {
        when(ollama.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> ask("Xin chào")).isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getResponseCode()).isEqualTo(AssistantResponseCode.AI_UNAVAILABLE));
    }
}
