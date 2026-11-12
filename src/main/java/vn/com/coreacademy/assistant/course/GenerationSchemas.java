package vn.com.coreacademy.assistant.course;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The shape every model output must have before it is stored. Each check returns the problems it found, empty when the
 * output is valid; the text of a problem is for the log and for the retry hint, never shown to the author. The limits
 * match what the draft can hold, so a valid output always fits {@code DraftContent}.
 */
public final class GenerationSchemas {
    static final int MAX_CHAPTERS = 20;
    static final int MAX_LESSONS_PER_CHAPTER = 10;
    static final int MAX_TOTAL_LESSONS = 60;
    static final int MAX_BLOCKS = 6;
    static final int MAX_BODY = 6000;
    static final int MAX_TITLE = 255;
    static final int MAX_STEPS = 30;
    static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");

    /** Links, images and HTML in model text: the skill forbids them, and a source document must not smuggle them in. */
    private static final Pattern LINK = Pattern.compile("(?i)(https?:|ftp:|javascript:)|www\\.|\\]\\(|<\\s*[a-z/!]");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GenerationSchemas() {
    }

    /**
     * The JSON schema Ollama is given as {@code format} for a step, so the model cannot answer in another shape. It
     * states the same limits as the checks below; the checks stay as the last word, since a schema cannot say
     * "no links" or "exactly one correct option".
     */
    public static JsonNode format(String stepKind) {
        return switch (stepKind) {
            case "summarise_sections" -> object(Map.of("title", string(MAX_TITLE), "summary", string(4000),
                "keyPoints", array(string(1000), 0, 8)), "title", "summary", "keyPoints");
            case "outline" -> object(Map.of("title", string(MAX_TITLE), "summary", string(4000),
                "objectives", array(string(2000), 0, 20),
                "chapters", array(object(Map.of("title", string(MAX_TITLE), "lessons", array(object(Map.of(
                    "title", string(MAX_TITLE), "objectives", array(string(2000), 0, 10),
                    "estimatedMinutes", integer(1, 240)), "title", "objectives", "estimatedMinutes"), 1,
                    MAX_LESSONS_PER_CHAPTER)), "title", "lessons"), 1, MAX_CHAPTERS)),
                "title", "summary", "objectives", "chapters");
            case "lesson" -> object(Map.of("blocks", array(object(Map.of("type", enumOf("text", "process"),
                "title", string(MAX_TITLE), "body", string(MAX_BODY), "steps", array(object(Map.of(
                    "title", string(MAX_TITLE), "description", string(2000)), "title"), 1, MAX_STEPS)),
                "type", "title"), 1, MAX_BLOCKS),
                "sourceNotes", array(object(Map.of("note", string(1000)), "note"), 0, 30)), "blocks", "sourceNotes");
            case "questions" -> object(Map.of("questions", array(object(Map.of("type", enumOf("SINGLE", "MULTIPLE"),
                "content", string(2000), "explanation", string(2000),
                "difficulty", enumOf("EASY", "MEDIUM", "HARD"),
                "options", array(object(Map.of("content", string(1000), "correct", MAPPER.createObjectNode()
                    .put("type", "boolean")), "content", "correct"), 2, 6)),
                "type", "content", "difficulty", "options"), 1, 8)), "questions");
            default -> throw new IllegalArgumentException("No schema for step " + stepKind);
        };
    }

    private static ObjectNode object(Map<String, JsonNode> properties, String... required) {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        ObjectNode props = schema.putObject("properties");
        properties.forEach(props::set);
        ArrayNode names = schema.putArray("required");
        for (String name : required) {
            names.add(name);
        }
        return schema;
    }

    private static ObjectNode array(JsonNode items, int min, int max) {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "array").put("minItems", min).put("maxItems", max);
        schema.set("items", items);
        return schema;
    }

    private static ObjectNode string(int max) {
        return MAPPER.createObjectNode().put("type", "string").put("minLength", 1).put("maxLength", max);
    }

    private static ObjectNode integer(int min, int max) {
        return MAPPER.createObjectNode().put("type", "integer").put("minimum", min).put("maximum", max);
    }

    private static ObjectNode enumOf(String... values) {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "string");
        ArrayNode names = schema.putArray("enum");
        for (String value : values) {
            names.add(value);
        }
        return schema;
    }

    public static List<String> summary(JsonNode node) {
        List<String> problems = new ArrayList<>();
        text(node, "title", 255, problems, "summary");
        text(node, "summary", 4000, problems, "summary");
        array(node, "keyPoints", 0, 8, problems, "summary");
        return problems;
    }

    public static List<String> outline(JsonNode node) {
        List<String> problems = new ArrayList<>();
        text(node, "title", MAX_TITLE, problems, "outline");
        text(node, "summary", 4000, problems, "outline");
        strings(node.path("objectives"), 0, 20, problems, "outline.objectives");
        JsonNode chapters = node.path("chapters");
        if (!chapters.isArray() || chapters.isEmpty() || chapters.size() > MAX_CHAPTERS) {
            problems.add("outline.chapters must have 1 to " + MAX_CHAPTERS + " items");
            return problems;
        }
        int number = 0;
        int totalLessons = 0;
        for (JsonNode chapter : chapters) {
            number++;
            totalLessons += chapter.path("lessons").size();
            text(chapter, "title", MAX_TITLE, problems, "chapter " + number);
            JsonNode lessons = chapter.path("lessons");
            if (!lessons.isArray() || lessons.isEmpty() || lessons.size() > MAX_LESSONS_PER_CHAPTER) {
                problems.add("chapter " + number + " must have 1 to " + MAX_LESSONS_PER_CHAPTER + " lessons");
                continue;
            }
            for (JsonNode lesson : lessons) {
                text(lesson, "title", MAX_TITLE, problems, "lesson of chapter " + number);
                strings(lesson.path("objectives"), 0, 10, problems, "lesson objectives");
                int minutes = lesson.path("estimatedMinutes").asInt(-1);
                if (!lesson.path("estimatedMinutes").isInt() || minutes < 1 || minutes > 240) {
                    problems.add("lesson estimatedMinutes must be 1 to 240");
                }
            }
        }
        if (totalLessons > MAX_TOTAL_LESSONS) {
            problems.add("outline must have at most " + MAX_TOTAL_LESSONS + " lessons in total");
        }
        return problems;
    }

    public static List<String> lesson(JsonNode node) {
        List<String> problems = new ArrayList<>();
        JsonNode blocks = node.path("blocks");
        if (!blocks.isArray() || blocks.isEmpty() || blocks.size() > MAX_BLOCKS) {
            problems.add("lesson.blocks must have 1 to " + MAX_BLOCKS + " items");
            return problems;
        }
        for (JsonNode block : blocks) {
            String type = block.path("type").asText("");
            text(block, "title", MAX_TITLE, problems, "block");
            if (type.equals("text")) {
                text(block, "body", MAX_BODY, problems, "text block");
            } else if (type.equals("process")) {
                JsonNode steps = block.path("steps");
                if (!steps.isArray() || steps.isEmpty() || steps.size() > MAX_STEPS) {
                    problems.add("process block must have 1 to " + MAX_STEPS + " steps");
                    continue;
                }
                for (JsonNode step : steps) {
                    text(step, "title", MAX_TITLE, problems, "process step");
                    if (step.has("description") && !step.path("description").isTextual()) {
                        problems.add("process step description must be text");
                    }
                }
            } else {
                problems.add("block type must be text or process");
            }
        }
        noLinks(node, problems, "lesson");
        JsonNode notes = node.path("sourceNotes");
        if (!notes.isArray() || notes.size() > 30) {
            problems.add("lesson.sourceNotes must be an array of up to 30 items");
        } else {
            for (JsonNode note : notes) {
                text(note, "note", 1000, problems, "source note");
            }
        }
        return problems;
    }

    public static List<String> questions(JsonNode node) {
        List<String> problems = new ArrayList<>();
        JsonNode questions = node.path("questions");
        if (!questions.isArray() || questions.isEmpty() || questions.size() > 8) {
            problems.add("questions must have 1 to 8 items");
            return problems;
        }
        noLinks(node, problems, "questions");
        for (JsonNode question : questions) {
            String type = question.path("type").asText("");
            if (!type.equals("SINGLE") && !type.equals("MULTIPLE")) {
                problems.add("question type must be SINGLE or MULTIPLE");
            }
            text(question, "content", 2000, problems, "question");
            if (question.has("explanation") && !question.path("explanation").isTextual()) {
                problems.add("question explanation must be text");
            }
            if (!DIFFICULTIES.contains(question.path("difficulty").asText(""))) {
                problems.add("question difficulty must be EASY, MEDIUM or HARD");
            }
            JsonNode options = question.path("options");
            if (!options.isArray() || options.size() < 2 || options.size() > 6) {
                problems.add("question options must have 2 to 6 items");
                continue;
            }
            int correct = 0;
            for (JsonNode option : options) {
                text(option, "content", 1000, problems, "option");
                if (!option.path("correct").isBoolean()) {
                    problems.add("option correct must be true or false");
                } else if (option.path("correct").asBoolean()) {
                    correct++;
                }
            }
            if (type.equals("SINGLE") && correct != 1) {
                problems.add("a SINGLE question needs exactly one correct option");
            }
            if (type.equals("MULTIPLE") && correct < 2) {
                problems.add("a MULTIPLE question needs at least two correct options");
            }
        }
        return problems;
    }

    private static void noLinks(JsonNode node, List<String> problems, String where) {
        if (node.isTextual() && LINK.matcher(node.asText()).find()) {
            problems.add(where + " must not contain links, images or HTML");
        }
        node.forEach(child -> noLinks(child, problems, where));
    }

    private static void text(JsonNode node, String field, int max, List<String> problems, String where) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) {
            problems.add(where + "." + field + " must be text of 1 to " + max + " characters");
        }
    }

    private static void array(JsonNode node, String field, int min, int max, List<String> problems, String where) {
        JsonNode value = node.path(field);
        if (!value.isArray() || value.size() < min || value.size() > max) {
            problems.add(where + "." + field + " must be an array of " + min + " to " + max + " items");
        }
    }

    private static void strings(JsonNode value, int min, int max, List<String> problems, String where) {
        if (!value.isArray() || value.size() < min || value.size() > max) {
            problems.add(where + " must be an array of " + min + " to " + max + " texts");
            return;
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 2000) {
                problems.add(where + " must contain texts of 1 to 2000 characters");
                return;
            }
        }
    }
}
