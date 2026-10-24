package vn.com.coreacademy.knowledge.ai;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;

/** One call the model made, in Ollama's wire shape {@code {"function":{"name","arguments"}}}. */
public record ToolCall(Function function) {
    public record Function(String name, JsonNode arguments) {
    }

    public static ToolCall of(String name, JsonNode arguments) {
        return new ToolCall(new Function(name, arguments));
    }

    @JsonIgnore
    public String name() {
        return function.name();
    }

    @JsonIgnore
    public JsonNode arguments() {
        return function.arguments();
    }
}
