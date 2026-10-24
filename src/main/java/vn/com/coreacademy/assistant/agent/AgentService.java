package vn.com.coreacademy.assistant.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.dto.AgentMessage;
import vn.com.coreacademy.assistant.dto.AgentRequest;
import vn.com.coreacademy.assistant.dto.AgentStreamEvent;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.assistant.tool.AgentTool;
import vn.com.coreacademy.assistant.tool.FinishedAnswer;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.service.AuthService;
import vn.com.coreacademy.knowledge.ai.ChatMessage;
import vn.com.coreacademy.knowledge.ai.ChatTurn;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.ai.ToolCall;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;

/**
 * Runs one user turn: asks the local model with the tools this user may use, runs the tools it calls and streams the
 * answer. Read tools run inside the loop and their result goes back to the model; tools that navigate or propose a
 * data change end the turn, so nothing that changes data ever runs here. Once a tool has put shared text (document
 * chunks) into the turn, the tools that act for the user are no longer offered and are refused if called anyway, so
 * an instruction planted in a document cannot open a screen or propose a change. Messages, arguments and tool results are
 * never logged: only tool names, counts and durations are.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {
    private static final int MAX_USER_LENGTH = 500;
    private static final int MAX_ASSISTANT_LENGTH = 4000;

    static final String UNKNOWN_TOOL = "Công cụ không tồn tại.";
    static final String BAD_ARGUMENTS = "Tham số của công cụ không hợp lệ.";
    public static final String ACTION_AFTER_DOCUMENTS = "Không thể mở màn hình hay đề xuất thay đổi trong lượt "
        + "đã đọc tài liệu. Hãy trả lời câu hỏi, người dùng có thể gửi yêu cầu đó riêng.";
    public static final String EMPTY_ANSWER = "Mình chưa có câu trả lời cho yêu cầu này. Bạn thử hỏi lại nhé.";
    static final String STEP_CAP_ANSWER =
        "Mình chưa xử lý được yêu cầu này. Bạn thử diễn đạt lại ngắn gọn hơn nhé.";

    private final OllamaClient ollama;
    private final AuthService auth;
    private final ToolRegistry registry;
    private final AssistantProperties properties;
    private final ObjectMapper json;

    /**
     * Validates the request, then streams the turn. Roles other than {@code user} and {@code assistant} are refused,
     * so a client cannot inject tool calls or results; a long {@code assistant} text is cut, since it is the
     * server's own earlier answer.
     */
    public StreamingResponseBody stream(AgentRequest request) {
        return stream(history(request));
    }

    private List<ChatMessage> history(AgentRequest request) {
        List<AgentMessage> messages = request.messages();
        if (messages == null || messages.isEmpty() || messages.size() > properties.historyMessages()) {
            throw BusinessException.validation("messages", "INVALID_LENGTH",
                "Hội thoại phải có từ 1 đến " + properties.historyMessages() + " tin nhắn.");
        }

        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            String field = "messages[" + i + "]";
            String content = message == null || message.content() == null ? "" : message.content().strip();
            String role = message == null ? null : message.role();
            if ("user".equals(role)) {
                if (content.isEmpty() || content.length() > MAX_USER_LENGTH) {
                    throw BusinessException.validation(field + ".content", "OUT_OF_RANGE",
                        "Tin nhắn phải từ 1 đến " + MAX_USER_LENGTH + " ký tự.");
                }
                history.add(ChatMessage.user(content));
            } else if ("assistant".equals(role)) {
                history.add(ChatMessage.assistant(content.length() > MAX_ASSISTANT_LENGTH
                    ? content.substring(0, MAX_ASSISTANT_LENGTH) : content));
            } else {
                throw BusinessException.validation(field + ".role", "INVALID_VALUE",
                    "Vai trò chỉ có thể là user hoặc assistant.");
            }
        }
        if (!"user".equals(history.get(history.size() - 1).role())) {
            throw BusinessException.validation("messages", "INVALID_VALUE",
                "Tin nhắn cuối cùng phải của người dùng.");
        }

        return history;
    }

    /**
     * The context and the tool list are built now, on the request thread, so a refusal is still an ordinary envelope
     * and the stream body never needs the security context. {@code history} holds the request's text turns only.
     */
    public StreamingResponseBody stream(List<ChatMessage> history) {
        AgentContext context = AgentContext.of(AssistantAccess.require(auth.me()));
        if (!ollama.isAvailable()) {
            throw new BusinessException(AssistantResponseCode.AI_UNAVAILABLE);
        }

        List<AgentTool> tools = registry.availableTo(context);
        List<ToolDefinition> definitions = tools.stream().map(tool -> tool.definition(context)).toList();
        return output -> run(output, history, context, tools, definitions);
    }

    private void run(OutputStream output, List<ChatMessage> history, AgentContext context, List<AgentTool> tools,
                     List<ToolDefinition> definitions) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(AgentPrompt.SYSTEM));
        messages.addAll(history);
        TurnState turn = new TurnState();
        List<ToolDefinition> offered = definitions;
        boolean readShared = false;

        try {
            for (int step = 0; step < properties.maxSteps(); step++) {
                ChatTurn answer = ollama.chatWithTools(List.copyOf(messages), offered, OllamaClient.Options.AGENT,
                    delta -> write(output, AgentStreamEvent.delta(delta)));
                if (answer.toolCalls().isEmpty()) {
                    finish(output, turn, answer.content().strip());
                    return;
                }

                messages.add(ChatMessage.assistant(answer.content(), answer.toolCalls()));
                for (ToolCall call : answer.toolCalls()) {
                    write(output, AgentStreamEvent.tool(call.name()));
                    AgentTool tool = find(tools, call.name());
                    ToolOutcome outcome = tool != null && tool.actsForUser() && readShared
                        ? new ToolOutcome.Result(ACTION_AFTER_DOCUMENTS)
                        : runTool(call, tool, context, turn);
                    if (end(output, outcome)) {
                        return;
                    }
                    if (tool != null && tool.readsSharedContent() && !readShared) {
                        readShared = true;
                        offered = withoutActingTools(tools, definitions);
                    }
                    messages.add(ChatMessage.tool(call.name(), ((ToolOutcome.Result) outcome).text()));
                }
            }
            write(output, AgentStreamEvent.done(STEP_CAP_ANSWER, List.of()));
        } catch (UncheckedIOException exception) {
            // The client went away: nobody is left to read an error event.
            throw exception;
        } catch (OllamaUnavailableException | OllamaException exception) {
            write(output, AgentStreamEvent.error(AssistantResponseCode.AI_UNAVAILABLE.getCode(),
                AssistantResponseCode.AI_UNAVAILABLE.getMessage()));
        } catch (RuntimeException exception) {
            // The message could quote the user's text or profile; log the type and the stack only.
            log.error("Assistant turn failed type={} stack={}", exception.getClass().getName(),
                Arrays.toString(exception.getStackTrace()));
            write(output, AgentStreamEvent.error(ResponseCode.INTERNAL_ERROR.getCode(),
                ResponseCode.INTERNAL_ERROR.getMessage()));
        }
    }

    private static AgentTool find(List<AgentTool> tools, String name) {
        return tools.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElse(null);
    }

    /** The definitions of the tools that do not act for the user; {@code definitions} is in {@code tools} order. */
    private static List<ToolDefinition> withoutActingTools(List<AgentTool> tools, List<ToolDefinition> definitions) {
        List<ToolDefinition> kept = new ArrayList<>();
        for (int i = 0; i < tools.size(); i++) {
            if (!tools.get(i).actsForUser()) {
                kept.add(definitions.get(i));
            }
        }

        return kept;
    }

    private ToolOutcome runTool(ToolCall call, AgentTool tool, AgentContext context, TurnState turn) {
        if (tool == null) {
            return new ToolOutcome.Result(UNKNOWN_TOOL);
        }

        JsonNode arguments = call.arguments();
        if (arguments == null || !arguments.isObject()) {
            return new ToolOutcome.Result(BAD_ARGUMENTS);
        }

        long started = System.nanoTime();
        ToolOutcome outcome = tool.run(arguments, context, turn);
        log.info("Assistant tool name={} outcome={} durationMs={}", tool.name(), outcome.getClass().getSimpleName(),
            (System.nanoTime() - started) / 1_000_000);
        return outcome;
    }

    /** Writes the closing events of a terminal outcome; false for a result that goes back to the model. */
    private boolean end(OutputStream output, ToolOutcome outcome) {
        if (outcome instanceof ToolOutcome.Navigate navigate) {
            write(output, AgentStreamEvent.navigate(navigate.path(), navigate.title()));
            write(output, AgentStreamEvent.done(navigate.text(), List.of()));
            return true;
        }
        if (outcome instanceof ToolOutcome.ActionRequired required) {
            write(output, AgentStreamEvent.actionRequired(required.action().view()));
            write(output, AgentStreamEvent.done(required.text(), List.of()));
            return true;
        }

        return false;
    }

    private void finish(OutputStream output, TurnState turn, String answer) {
        FinishedAnswer finished = turn.finisher().map(finisher -> finisher.finish(answer))
            .orElseGet(() -> new FinishedAnswer(answer, List.of()));
        if (finished.answer().isBlank()) {
            // qwen3 sometimes ends with nothing after a tool result; an empty bubble reads as a hang.
            finished = new FinishedAnswer(EMPTY_ANSWER, List.of());
        }
        write(output, AgentStreamEvent.done(finished.answer(), finished.citations()));
    }

    private void write(OutputStream output, AgentStreamEvent event) {
        try {
            output.write(json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialise an agent event", exception);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
