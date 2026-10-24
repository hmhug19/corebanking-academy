package vn.com.coreacademy.knowledge.ai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A message of an agent conversation. Absent parts are not serialised, so a plain message has the same
 * {@code role}/{@code content} shape as {@link OllamaClient.Message}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
    String role,
    String content,
    @JsonProperty("tool_calls") List<ToolCall> toolCalls,
    @JsonProperty("tool_name") String toolName) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null, null);
    }

    /** An assistant step that called tools. */
    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, null);
    }

    /** The result of the tool named {@code toolName}. */
    public static ChatMessage tool(String toolName, String content) {
        return new ChatMessage("tool", content, null, toolName);
    }
}
