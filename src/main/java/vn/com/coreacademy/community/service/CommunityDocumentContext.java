package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.common.exception.BusinessException;

/** Metadata có schema theo kind; không thực thi expression/code trong nội dung hoặc context. */
public final class CommunityDocumentContext {
    public static void validate(JsonNode context, String kind, boolean complete) {
        String key = switch (kind) {
            case "wiki" -> "wiki";
            case "radar" -> "radar";
            case "recording" -> "recording";
            case "architecture_review" -> "review";
            case "lesson_learned" -> "lessonLearned";
            default -> null;
        };
        if (key == null) {
            return;
        }
        JsonNode value = context == null ? null : context.get(key);
        if (value == null && !complete) {
            return;
        }
        if (value == null || !value.isObject()) {
            invalid(key);
        }
        switch (key) {
            case "wiki" -> {
                fields(value, Set.of("documentClass", "approvalScope"));
                choice(value, "documentClass", Set.of("guide", "convention", "template", "standard"), complete);
                text(value, "approvalScope", "standard".equals(value.path("documentClass").asText()) ? 1 : 0,
                    3_000, complete && "standard".equals(value.path("documentClass").asText()));
            }
            case "radar" -> {
                fields(value, Set.of("technologyName", "technologyVersion", "stage", "approvalScope", "effectiveFrom"));
                text(value, "technologyName", 1, 255, complete);
                text(value, "technologyVersion", 0, 255, false);
                choice(value, "stage", Set.of("assess", "trial", "adopt", "hold"), complete);
                text(value, "approvalScope", 1, 3_000, complete);
                date(value, "effectiveFrom", false);
            }
            case "recording" -> {
                fields(value, Set.of("heldAt", "speakers", "description"));
                if (complete && !value.hasNonNull("heldAt")) {
                    invalid("heldAt");
                }
                date(value, "heldAt", true);
                text(value, "description", 0, 10_000, false);
                JsonNode speakers = value.get("speakers");
                if (speakers == null && !complete) {
                    break;
                }
                if (speakers == null || !speakers.isArray() || speakers.isEmpty() || speakers.size() > 20) {
                    invalid("speakers");
                }
                for (JsonNode speaker : speakers) {
                    fields(speaker, Set.of("displayName", "userId"));
                    text(speaker, "displayName", 1, 255, true);
                    text(speaker, "userId", 1, 20, false);
                }
            }
            case "review" -> {
                fields(value, Set.of("problem", "constraints", "decisionsNeeded"));
                text(value, "problem", complete ? 20 : 0, 10_000, complete);
                text(value, "constraints", 0, 20_000, false);
                JsonNode decisions = value.get("decisionsNeeded");
                if (decisions == null && !complete) {
                    break;
                }
                if (decisions == null || !decisions.isArray() || complete && decisions.isEmpty() || decisions.size() > 20) {
                    invalid("decisionsNeeded");
                }
                for (JsonNode decision : decisions) {
                    if (!decision.isTextual() || complete && decision.asText().isBlank() || decision.asText().length() > 3_000) {
                        invalid("decisionsNeeded");
                    }
                }
            }
            case "lessonLearned" -> {
                fields(value, Set.of("occurredOn", "symptoms", "rootCause", "resolution", "prevention"));
                if (complete && !value.hasNonNull("occurredOn")) {
                    invalid("occurredOn");
                }
                date(value, "occurredOn", false);
                text(value, "symptoms", 20, 10_000, complete);
                text(value, "rootCause", 20, 10_000, complete);
                text(value, "resolution", 20, 20_000, complete);
                text(value, "prevention", 20, 10_000, complete);
            }
            default -> throw new IllegalStateException("Unknown context schema");
        }
    }

    private static void fields(JsonNode value, Set<String> allowed) {
        if (!value.isObject()) {
            invalid("context");
        }
        value.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                invalid(field);
            }
        });
    }

    private static void text(JsonNode value, String field, int min, int max, boolean required) {
        JsonNode node = value.get(field);
        if (node == null && !required) {
            return;
        }
        if (node == null || !node.isTextual() || node.asText().trim().length() < min || node.asText().length() > max) {
            invalid(field);
        }
    }

    private static void choice(JsonNode value, String field, Set<String> allowed, boolean required) {
        if (!value.has(field) && !required) {
            return;
        }
        if (!value.path(field).isTextual() || !allowed.contains(value.path(field).asText())) {
            invalid(field);
        }
    }

    private static void date(JsonNode value, String field, boolean timestamp) {
        if (!value.has(field)) {
            return;
        }
        if (!value.get(field).isTextual()) {
            invalid(field);
        }
        try {
            if (timestamp) {
                Instant.parse(value.get(field).asText());
            } else {
                LocalDate.parse(value.get(field).asText());
            }
        } catch (DateTimeParseException exception) {
            invalid(field);
        }
    }

    private static void invalid(String field) {
        throw BusinessException.validation("context." + field, "INVALID_VALUE", "Metadata nội dung chưa hợp lệ.");
    }

    private CommunityDocumentContext() {
    }
}
