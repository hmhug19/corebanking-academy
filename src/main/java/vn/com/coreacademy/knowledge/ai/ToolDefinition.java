package vn.com.coreacademy.knowledge.ai;

import com.fasterxml.jackson.databind.JsonNode;

/** A function the model may call; {@code parameters} is a JSON Schema object. */
public record ToolDefinition(String name, String description, JsonNode parameters) {
}
