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
    /** A course (and chapter, lesson) title, as the course service stores it. */
    public static final int MAX_TITLE = 255;
    /** What the author says the course is for (targetLearners). */
    public static final int MAX_TARGET_LEARNERS = 500;
    static final int MAX_STEPS = 30;
    static final Set<String> DIFFICULTIES = Set.of("EASY", "MEDIUM", "HARD");
    /** Step 1 of Soạn cùng trợ lý: the basic information of the course. */
    public static final String DRAFT_BASICS = "draft_basics";
    static final int MAX_DRAFT_SUMMARY = 2000;
    static final int MAX_DRAFT_OBJECTIVES = 10;
    /** The course's level and kind ("Loại khóa học"), as the course stores them. */
    public static final List<String> LEVELS = List.of("basic", "intermediate", "advanced");
    public static final List<String> REQUIREMENTS = List.of("mandatory", "optional");
    /** Step 2 of Soạn cùng trợ lý: chapters and lesson titles, one line per lesson, no lesson content. */
    public static final String DRAFT_OUTLINE = "draft_outline";
    /** One line per lesson: what it teaches. */
    public static final int MAX_LESSON_DESCRIPTION = 300;
    static final int MAX_SECTION_KEY = 20;
    /** Step 3 of Soạn cùng trợ lý: bonus points, a competency and draft final exam questions. */
    public static final String DRAFT_RULES = "draft_rules";
    /** About ten questions are asked for; a few more are accepted. */
    public static final int MAX_DRAFT_QUESTIONS = 15;
    /** The bonus points the model may propose; the course itself accepts more. */
    public static final int MAX_DRAFT_REWARD = 1000;
    /** Step 4 of Soạn cùng trợ lý: who the course is assigned to, picked from the units the author may assign. */
    public static final String DRAFT_AUDIENCE = "draft_audience";
    /** The assignment targets the model may propose. */
    public static final int MAX_DRAFT_TARGETS = 10;
    static final int MAX_TARGET_KEY = 20;
    /** The step 1 fields the model may say it is unsure of. */
    public static final List<String> BASICS_FIELDS = List.of("title", "summary", "objectives", "level",
        "defaultRequirement", "programId", "moduleId");

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
            case DRAFT_BASICS -> object(Map.of("title", string(MAX_TITLE), "summary", string(MAX_DRAFT_SUMMARY),
                "objectives", array(string(2000), 1, MAX_DRAFT_OBJECTIVES),
                "level", enumOf(LEVELS.toArray(String[]::new)),
                "defaultRequirement", enumOf(REQUIREMENTS.toArray(String[]::new)),
                "programId", integer(1, Integer.MAX_VALUE), "moduleId", integer(1, Integer.MAX_VALUE),
                "unsure", array(enumOf(BASICS_FIELDS.toArray(String[]::new)), 0, BASICS_FIELDS.size())),
                "title", "summary", "objectives", "level", "defaultRequirement", "unsure");
            case DRAFT_OUTLINE -> object(Map.of("chapters", array(object(Map.of("title", string(MAX_TITLE),
                "lessons", array(object(Map.of("title", string(MAX_TITLE),
                    "description", string(MAX_LESSON_DESCRIPTION), "section", string(MAX_SECTION_KEY)),
                    "title", "description", "section"), 1, MAX_LESSONS_PER_CHAPTER)), "title", "lessons"), 1,
                MAX_CHAPTERS)), "chapters");
            case DRAFT_RULES -> object(Map.of("rewardPoints", integer(0, MAX_DRAFT_REWARD),
                "competencyId", integer(1, Integer.MAX_VALUE),
                "questions", array(object(Map.of("type", enumOf("SINGLE", "MULTIPLE"),
                    "content", string(2000), "explanation", string(2000),
                    "difficulty", enumOf("EASY", "MEDIUM", "HARD"),
                    "options", array(object(Map.of("content", string(1000), "correct", MAPPER.createObjectNode()
                        .put("type", "boolean")), "content", "correct"), 2, 6),
                    "section", string(MAX_SECTION_KEY)),
                    "type", "content", "difficulty", "options", "section"), 1, MAX_DRAFT_QUESTIONS)),
                "rewardPoints", "questions");
            case DRAFT_AUDIENCE -> object(Map.of("targets", array(object(Map.of("key", string(MAX_TARGET_KEY),
                "includeDescendants", MAPPER.createObjectNode().put("type", "boolean")), "key"), 0,
                MAX_DRAFT_TARGETS)), "targets");
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
        text(node, "title", MAX_TITLE, problems, "summary");
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
            question(question, problems);
        }
        return problems;
    }

    /** One multiple choice question: its type, text, difficulty and 2 to 6 options with the right number correct. */
    private static void question(JsonNode question, List<String> problems) {
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
            return;
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

    /**
     * Step 1 of Soạn cùng trợ lý as the model wrote it: title, short description, 1 to 10 objectives, a known level and
     * kind, optional programme and module ids (checked against the lists later, not here) and the fields it is unsure of.
     */
    public static List<String> draftBasics(JsonNode node) {
        List<String> problems = new ArrayList<>();
        text(node, "title", MAX_TITLE, problems, "basics");
        text(node, "summary", MAX_DRAFT_SUMMARY, problems, "basics");
        strings(node.path("objectives"), 1, MAX_DRAFT_OBJECTIVES, problems, "basics.objectives");
        if (!LEVELS.contains(node.path("level").asText(""))) {
            problems.add("basics.level must be one of " + LEVELS);
        }
        if (!REQUIREMENTS.contains(node.path("defaultRequirement").asText(""))) {
            problems.add("basics.defaultRequirement must be one of " + REQUIREMENTS);
        }
        for (String id : List.of("programId", "moduleId")) {
            JsonNode value = node.path(id);
            if (!value.isMissingNode() && !value.isNull() && !(value.canConvertToLong() && value.isIntegralNumber())) {
                problems.add("basics." + id + " must be a whole number or absent");
            }
        }
        JsonNode unsure = node.path("unsure");
        if (!unsure.isArray()) {
            problems.add("basics.unsure must be an array");
        } else {
            unsure.forEach(field -> {
                if (!BASICS_FIELDS.contains(field.asText(""))) {
                    problems.add("basics.unsure may only name " + BASICS_FIELDS);
                }
            });
        }
        noLinks(node, problems, "basics");
        return problems;
    }

    /**
     * Step 2 of Soạn cùng trợ lý as the model wrote it: 1 to 20 chapters of 1 to 10 lessons (60 at most), each lesson a
     * title, one line saying what it teaches and the key of the source section it comes from (checked against the
     * summary's sections by the proposer, not here). Lessons carry no content.
     */
    public static List<String> draftOutline(JsonNode node) {
        List<String> problems = new ArrayList<>();
        JsonNode chapters = node.path("chapters");
        if (!chapters.isArray() || chapters.isEmpty() || chapters.size() > MAX_CHAPTERS) {
            problems.add("outline.chapters must have 1 to " + MAX_CHAPTERS + " items");
            return problems;
        }
        int number = 0;
        int totalLessons = 0;
        for (JsonNode chapter : chapters) {
            number++;
            text(chapter, "title", MAX_TITLE, problems, "chapter " + number);
            JsonNode lessons = chapter.path("lessons");
            if (!lessons.isArray() || lessons.isEmpty() || lessons.size() > MAX_LESSONS_PER_CHAPTER) {
                problems.add("chapter " + number + " must have 1 to " + MAX_LESSONS_PER_CHAPTER + " lessons");
                continue;
            }
            totalLessons += lessons.size();
            for (JsonNode lesson : lessons) {
                text(lesson, "title", MAX_TITLE, problems, "lesson of chapter " + number);
                text(lesson, "description", MAX_LESSON_DESCRIPTION, problems, "lesson of chapter " + number);
                text(lesson, "section", MAX_SECTION_KEY, problems, "lesson of chapter " + number);
            }
        }
        if (totalLessons > MAX_TOTAL_LESSONS) {
            problems.add("outline must have at most " + MAX_TOTAL_LESSONS + " lessons in total");
        }
        noLinks(node, problems, "outline");
        return problems;
    }

    /**
     * Step 3 of Soạn cùng trợ lý as the model wrote it: bonus points from 0 to {@value #MAX_DRAFT_REWARD}, an optional
     * competency id (checked against the list later, not here) and 1 to {@value #MAX_DRAFT_QUESTIONS} questions, each
     * naming the key of the source section it comes from (checked against the summary by the proposer).
     */
    public static List<String> draftRules(JsonNode node) {
        List<String> problems = new ArrayList<>();
        JsonNode points = node.path("rewardPoints");
        if (!points.isIntegralNumber() || points.asInt() < 0 || points.asInt() > MAX_DRAFT_REWARD) {
            problems.add("rules.rewardPoints must be a whole number of 0 to " + MAX_DRAFT_REWARD);
        }
        JsonNode competency = node.path("competencyId");
        if (!competency.isMissingNode() && !competency.isNull() && !competency.isIntegralNumber()) {
            problems.add("rules.competencyId must be a whole number or absent");
        }
        JsonNode questions = node.path("questions");
        if (!questions.isArray() || questions.isEmpty() || questions.size() > MAX_DRAFT_QUESTIONS) {
            problems.add("rules.questions must have 1 to " + MAX_DRAFT_QUESTIONS + " items");
            return problems;
        }
        for (JsonNode question : questions) {
            question(question, problems);
            text(question, "section", MAX_SECTION_KEY, problems, "question");
        }
        noLinks(node, problems, "rules");
        return problems;
    }

    /**
     * Step 4 of Soạn cùng trợ lý as the model wrote it: 0 to {@value #MAX_DRAFT_TARGETS} targets, each the key of a
     * unit from the list it was given (checked against that list by the proposer, not here) and an optional
     * "include the units below" flag.
     */
    public static List<String> draftAudience(JsonNode node) {
        List<String> problems = new ArrayList<>();
        JsonNode targets = node.path("targets");
        if (!targets.isArray() || targets.size() > MAX_DRAFT_TARGETS) {
            problems.add("audience.targets must have 0 to " + MAX_DRAFT_TARGETS + " items");
            return problems;
        }
        for (JsonNode target : targets) {
            text(target, "key", MAX_TARGET_KEY, problems, "audience.target");
            JsonNode descendants = target.path("includeDescendants");
            if (!descendants.isMissingNode() && !descendants.isNull() && !descendants.isBoolean()) {
                problems.add("audience.target.includeDescendants must be true, false or absent");
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
