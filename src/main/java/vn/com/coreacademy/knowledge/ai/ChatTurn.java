package vn.com.coreacademy.knowledge.ai;

import java.util.List;

/** What the model produced in one call: the text it streamed and the tools it asked for, in order. */
public record ChatTurn(String content, List<ToolCall> toolCalls) {
}
