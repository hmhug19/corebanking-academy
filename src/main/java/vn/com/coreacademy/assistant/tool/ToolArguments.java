package vn.com.coreacademy.assistant.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** Reads the model's arguments defensively: it may send nothing, a number or an object where text is expected. */
public final class ToolArguments {
    private ToolArguments() {
    }

    public static boolean has(JsonNode arguments, String name) {
        return arguments != null && arguments.hasNonNull(name);
    }

    /** The trimmed text of {@code name}, or {@code null} when it is absent or not a string. */
    public static String text(JsonNode arguments, String name) {
        if (arguments == null || !arguments.path(name).isTextual()) {
            return null;
        }

        return arguments.path(name).asText().strip();
    }
}
