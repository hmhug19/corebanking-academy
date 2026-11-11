package vn.com.coreacademy.assistant.course;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.course.CourseJobRepository.JobRow;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.course.CourseJobViews.Inputs;
import vn.com.coreacademy.assistant.course.SourceTextRepository.Piece;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;
import vn.com.coreacademy.learning.dto.LearningRequests.BlockInput;
import vn.com.coreacademy.learning.dto.LearningRequests.StepInput;
import vn.com.coreacademy.learning.service.LearningAiDraftService;

/**
 * The model work of each generation step. Every output is parsed and checked against {@link GenerationSchemas}; an
 * invalid one is asked for once more with the problems named, then the step fails with a readable error. The source
 * text, the outline and the lesson are data inside the message, never instructions, and nothing of them is logged.
 */
@Component
@RequiredArgsConstructor
public class CourseGenerationRunner implements GenerationStepRunner {
    static final String SUMMARISE = "summarise_sections";
    static final String OUTLINE = "outline";
    static final String LESSON = "lesson";
    static final String QUESTIONS = "questions";
    static final String WAITING_APPROVAL = "waiting_approval";

    /** Tries per step: the first ask and two retries (Q24, Q41). */
    public static final int ATTEMPTS = 3;
    private static final Pattern KEY = Pattern.compile("c(\\d+)l(\\d+)");
    private static final int SECTION_CHARS = 4000;
    private static final int MAX_SECTIONS = 12;
    private static final int LESSON_SOURCE_CHARS = 6000;
    private static final int FIRST_PROGRESS = 15;
    private static final int OUTLINE_PROGRESS = 30;
    private static final int CONTENT_FROM = 35;
    private static final int CONTENT_SPAN = 55;

    private final CourseJobRepository jobs;
    private final SourceTextRepository sources;
    private final OllamaClient ollama;
    private final SkillCatalog catalog;
    private final AssistantModels models;
    private final ObjectMapper json;
    private final LearningAiDraftService drafts;

    @Override
    public Result run(StepRow step) {
        return switch (step.kind()) {
            case SUMMARISE -> summarise(step);
            case OUTLINE -> outline(step);
            case LESSON -> lesson(step);
            case QUESTIONS -> questions(step);
            default -> throw new GenerationException("Bước tạo khóa học không được hỗ trợ.");
        };
    }

    // ---- steps -----------------------------------------------------------------------------------------------------

    private Result summarise(StepRow step) {
        JobRow job = jobs.job(step.jobId()).orElseThrow(() -> new GenerationException("Không tìm thấy công việc."));
        if (job.sourceDocumentId() == null) {
            throw new GenerationException("Công việc không còn tài liệu nguồn.");
        }
        var status = sources.status(job.sourceDocumentId())
            .orElseThrow(() -> new GenerationException("Tài liệu nguồn đã bị xóa."));
        switch (status.status()) {
            case "queued", "processing" -> throw new StepNotReadyException("Source is still being read");
            case "failed" -> throw new GenerationException("Không đọc được tài liệu nguồn. Hãy thử tệp khác.");
            default -> { }
        }
        List<Piece> pieces = sources.pieces(job.sourceDocumentId());
        if (pieces.isEmpty()) {
            throw new GenerationException("Tài liệu nguồn không có nội dung để tạo khóa học.");
        }

        ArrayNode sections = json.createArrayNode();
        StringBuilder hash = new StringBuilder();
        List<List<Piece>> groups = group(pieces);
        int number = 0;
        for (List<Piece> group : groups) {
            number++;
            String user = "NGUỒN:\n" + join(group);
            JsonNode summary = ask(SUMMARISE, user, GenerationSchemas::summary);
            ObjectNode section = ((ObjectNode) summary).deepCopy();
            section.put("key", "s" + number);
            section.put("firstPiece", group.get(0).index());
            section.put("lastPiece", group.get(group.size() - 1).index());
            sections.add(section);
            hash.append(user.length()).append(':');
        }
        ObjectNode output = json.createObjectNode();
        output.set("sections", sections);

        return new Result(write(output), digest(hash.toString() + pieces.size()),
            List.of(new NextStep(OUTLINE, "")), FIRST_PROGRESS, null);
    }

    private Result outline(StepRow step) {
        JobRow job = jobs.job(step.jobId()).orElseThrow(() -> new GenerationException("Không tìm thấy công việc."));
        JsonNode sections = output(step.jobId(), SUMMARISE, "").path("sections");
        Inputs inputs = read(job.inputs(), Inputs.class);
        StringBuilder user = new StringBuilder("DỮ LIỆU\nĐối tượng học viên: ").append(inputs.targetLearners())
            .append("\nTrình độ: ").append(inputs.level());
        if (inputs.chapters() != null) {
            user.append("\nSố chương mong muốn: ").append(inputs.chapters());
        }
        if (inputs.durationMinutes() != null) {
            user.append("\nThời lượng mong muốn: ").append(inputs.durationMinutes()).append(" phút");
        }
        user.append("\n\nTóm tắt các phần của tài liệu nguồn:\n");
        for (JsonNode section : sections) {
            user.append("- ").append(section.path("title").asText()).append(": ")
                .append(section.path("summary").asText()).append('\n');
        }
        JsonNode outline = ask(OUTLINE, user.toString(), GenerationSchemas::outline);
        if (job.courseId() != null && job.revisionId() != null) {
            // The outline goes straight into the draft course, where the author edits it until they approve it.
            drafts.writeOutline(job.courseId(), job.revisionId(), job.ownerUserId(), outline.path("summary").asText(),
                CourseOutlineMapper.objectives(outline), CourseOutlineMapper.chapters(outline));
        }

        return new Result(write(outline), digest(user.toString()), List.of(), OUTLINE_PROGRESS, WAITING_APPROVAL);
    }

    private Result lesson(StepRow step) {
        JobRow job = jobs.job(step.jobId()).orElseThrow(() -> new GenerationException("Không tìm thấy công việc."));
        int[] place = CourseOutlineMapper.position(step.itemKey());
        boolean inCourse = job.courseId() != null && job.revisionId() != null && place != null;
        if (inCourse && !drafts.startLesson(job.courseId(), job.revisionId(), place[0], place[1], job.ownerUserId())) {
            // The author wrote this lesson themselves meanwhile: the job leaves it alone.
            ObjectNode skipped = json.createObjectNode();
            skipped.putArray("blocks");
            skipped.putArray("sourceNotes");
            skipped.put("skipped", "user_edited");
            return new Result(write(skipped), digest("skipped"), List.of(), contentProgress(step.jobId()), null);
        }
        JsonNode outline = output(step.jobId(), OUTLINE, "");
        JsonNode lesson = lessonOf(outline, step.itemKey());
        JsonNode chapter = chapterOf(outline, step.itemKey());
        List<Piece> pieces = lessonSource(step.jobId(), outline, lesson, chapter, step.itemKey());
        StringBuilder user = new StringBuilder("DỮ LIỆU\nKhóa học: ").append(outline.path("title").asText())
            .append("\nChương: ").append(chapter.path("title").asText())
            .append("\nBài: ").append(lesson.path("title").asText()).append("\nMục tiêu của bài:\n");
        lesson.path("objectives").forEach(objective -> user.append("- ").append(objective.asText()).append('\n'));
        user.append("\nĐOẠN NGUỒN:\n").append(join(pieces));
        JsonNode content = ask(LESSON, user.toString(), GenerationSchemas::lesson);
        if (inCourse && CourseJobRepository.RUNNING.equals(jobs.job(step.jobId()).map(JobRow::status).orElse(null))) {
            // A job stopped or cancelled while the model was writing leaves the lesson empty.
            drafts.writeLesson(job.courseId(), job.revisionId(), place[0], place[1], blocksOf(content),
                job.ownerUserId());
        }

        return new Result(write(content), digest(user.toString()), List.of(), contentProgress(step.jobId()), null);
    }

    private Result questions(StepRow step) {
        StepRow lessonStep = jobs.stepByKey(step.jobId(), LESSON, step.itemKey())
            .filter(row -> "done".equals(row.status()) && row.output() != null)
            .orElseThrow(() -> new StepNotReadyException("The lesson is not written yet"));
        JsonNode lesson = parse(lessonStep.output());
        if (lesson.has("skipped")) {
            ObjectNode none = json.createObjectNode();
            none.putArray("questions");
            return new Result(write(none), digest("skipped"), List.of(), contentProgress(step.jobId()), null);
        }
        JsonNode lessonOutline = lessonOf(output(step.jobId(), OUTLINE, ""), step.itemKey());
        StringBuilder user = new StringBuilder("DỮ LIỆU\nBài: ").append(lessonOutline.path("title").asText())
            .append("\n\nNội dung bài học:\n");
        for (JsonNode block : lesson.path("blocks")) {
            user.append("## ").append(block.path("title").asText()).append('\n');
            if (block.has("body")) {
                user.append(block.path("body").asText()).append('\n');
            }
            for (JsonNode part : block.path("steps")) {
                user.append("- ").append(part.path("title").asText()).append(": ")
                    .append(part.path("description").asText()).append('\n');
            }
        }
        JsonNode proposed = ask(QUESTIONS, user.toString(), GenerationSchemas::questions);
        ObjectNode output = ((ObjectNode) proposed).deepCopy();
        output.withArray("questions").forEach(question -> ((ObjectNode) question).put("decision", "pending"));

        return new Result(write(output), digest(user.toString()), List.of(), contentProgress(step.jobId()), null);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    static List<BlockInput> blocksOf(JsonNode lesson) {
        List<BlockInput> blocks = new ArrayList<>();
        for (JsonNode block : lesson.path("blocks")) {
            boolean process = "process".equals(block.path("type").asText());
            List<StepInput> steps = new ArrayList<>();
            if (process) {
                block.path("steps").forEach(part -> steps.add(new StepInput(part.path("title").asText(),
                    part.path("description").asText(null))));
            }
            blocks.add(new BlockInput(process ? "process" : "text", block.path("title").asText(),
                process ? null : block.path("body").asText(), process ? null : "markdown", "manual", null, true,
                List.of(), steps, null, null));
        }

        return blocks;
    }

    /**
     * Asks the model for JSON constrained to the step's schema (structured output), up to {@link #ATTEMPTS} times, each
     * retry naming the problems of the last answer; the checks of {@link GenerationSchemas} stay as the last word.
     */
    private JsonNode ask(String stepKind, String user, Function<JsonNode, List<String>> check) {
        String system = catalog.generationPrompt(stepKind);
        JsonNode format = GenerationSchemas.format(stepKind);
        List<String> problems = List.of();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String message = attempt == 0 ? user : user + "\n\nKết quả trước không hợp lệ ("
                + String.join("; ", problems.stream().limit(5).toList()) + "). Hãy trả lại đúng một đối tượng JSON.";
            String reply = ollama.chat(List.of(Message.system(system), Message.user(message)),
                models.generationOptions(), format);
            JsonNode node = extract(reply);
            problems = node == null ? List.of("không phải JSON hợp lệ") : check.apply(node);
            if (problems.isEmpty()) {
                return node;
            }
        }
        throw new GenerationException("Mô hình trả về dữ liệu không hợp lệ ở bước này. Hãy thử lại.");
    }

    /** The first JSON object in the reply, or null; models wrap JSON in text or code fences. */
    JsonNode extract(String reply) {
        if (reply == null) {
            return null;
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(reply.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static List<List<Piece>> group(List<Piece> pieces) {
        int total = pieces.stream().mapToInt(piece -> piece.content().length()).sum();
        int size = Math.max(SECTION_CHARS, (total + MAX_SECTIONS - 1) / MAX_SECTIONS);
        List<List<Piece>> groups = new ArrayList<>();
        List<Piece> current = new ArrayList<>();
        int length = 0;
        for (Piece piece : pieces) {
            if (!current.isEmpty() && length + piece.content().length() > size) {
                groups.add(current);
                current = new ArrayList<>();
                length = 0;
            }
            current.add(piece);
            length += piece.content().length();
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }

        return groups;
    }

    private static String join(List<Piece> pieces) {
        return String.join("\n\n", pieces.stream().map(Piece::labelled).toList());
    }

    /** The source pieces of the sections that talk most about the lesson; the lesson's place in the course breaks ties. */
    private List<Piece> lessonSource(long jobId, JsonNode outline, JsonNode lesson, JsonNode chapter, String key) {
        JsonNode sections = output(jobId, SUMMARISE, "").path("sections");
        JobRow job = jobs.job(jobId).orElseThrow(() -> new GenerationException("Không tìm thấy công việc."));
        List<Piece> all = sources.pieces(job.sourceDocumentId());
        if (all.isEmpty() || !sections.isArray() || sections.isEmpty()) {
            throw new GenerationException("Không còn nội dung nguồn để viết bài.");
        }
        Set<String> wanted = words(lesson.path("title").asText() + " " + chapter.path("title").asText() + " "
            + lesson.path("objectives").toString());
        int best = -1;
        int second = -1;
        int bestScore = 0;
        int secondScore = 0;
        for (int i = 0; i < sections.size(); i++) {
            JsonNode section = sections.get(i);
            Set<String> have = words(section.path("title").asText() + " " + section.path("summary").asText() + " "
                + section.path("keyPoints").toString());
            have.retainAll(wanted);
            int score = have.size();
            if (score > bestScore) {
                second = best;
                secondScore = bestScore;
                best = i;
                bestScore = score;
            } else if (score > secondScore) {
                second = i;
                secondScore = score;
            }
        }
        if (best < 0) {
            best = Math.min(sections.size() - 1, ordinal(outline, key) * sections.size() / lessonCount(outline));
        }
        List<Piece> chosen = new ArrayList<>();
        int length = 0;
        for (int section : second < 0 ? new int[] {best} : new int[] {best, second}) {
            int from = sections.get(section).path("firstPiece").asInt();
            int to = sections.get(section).path("lastPiece").asInt();
            for (Piece piece : all) {
                if (piece.index() >= from && piece.index() <= to && length < LESSON_SOURCE_CHARS) {
                    chosen.add(piece);
                    length += piece.content().length();
                }
            }
        }

        return chosen;
    }

    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                words.add(word);
            }
        }
        return words;
    }

    private static int lessonCount(JsonNode outline) {
        int count = 0;
        for (JsonNode chapter : outline.path("chapters")) {
            count += chapter.path("lessons").size();
        }
        return Math.max(1, count);
    }

    private static int ordinal(JsonNode outline, String key) {
        Matcher match = KEY.matcher(key);
        if (!match.matches()) {
            return 0;
        }
        int chapter = Integer.parseInt(match.group(1)) - 1;
        int lesson = Integer.parseInt(match.group(2)) - 1;
        int before = 0;
        for (int i = 0; i < chapter && i < outline.path("chapters").size(); i++) {
            before += outline.path("chapters").get(i).path("lessons").size();
        }
        return before + lesson;
    }

    static JsonNode chapterOf(JsonNode outline, String key) {
        Matcher match = KEY.matcher(key);
        if (!match.matches() || Integer.parseInt(match.group(1)) < 1
            || Integer.parseInt(match.group(1)) > outline.path("chapters").size()) {
            throw new GenerationException("Bài này không còn trong dàn ý.");
        }
        return outline.path("chapters").get(Integer.parseInt(match.group(1)) - 1);
    }

    static JsonNode lessonOf(JsonNode outline, String key) {
        JsonNode chapter = chapterOf(outline, key);
        Matcher match = KEY.matcher(key);
        match.matches();
        int lesson = Integer.parseInt(match.group(2));
        if (lesson < 1 || lesson > chapter.path("lessons").size()) {
            throw new GenerationException("Bài này không còn trong dàn ý.");
        }
        return chapter.path("lessons").get(lesson - 1);
    }

    /** From 35 to 90 as lessons and their questions are finished, counting the step being finished now. */
    private int contentProgress(long jobId) {
        long total = jobs.countSteps(jobId, LESSON, null) + jobs.countSteps(jobId, QUESTIONS, null);
        long done = jobs.countSteps(jobId, LESSON, "done") + jobs.countSteps(jobId, QUESTIONS, "done") + 1;

        return CONTENT_FROM + (int) Math.min(CONTENT_SPAN, CONTENT_SPAN * done / Math.max(1, total));
    }

    private JsonNode output(long jobId, String kind, String key) {
        return jobs.stepByKey(jobId, kind, key).filter(row -> row.output() != null).map(row -> parse(row.output()))
            .orElseThrow(() -> new GenerationException("Thiếu kết quả của bước trước."));
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String write(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
