package vn.com.coreacademy.knowledge.ai;

import java.util.List;

/**
 * What the model produced in one call: the text it streamed, the tools it asked for in order, and the token counts
 * Ollama reported.
 */
public record ChatTurn(String content, List<ToolCall> toolCalls, Usage usage) {
    public ChatTurn(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, Usage.NONE);
    }
}
