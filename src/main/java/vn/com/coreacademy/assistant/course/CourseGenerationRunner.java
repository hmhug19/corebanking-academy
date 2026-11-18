package vn.com.coreacademy.assistant.course;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.course.CourseJobRepository.JobRow;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.course.CourseJobViews.Inputs;
import vn.com.coreacademy.assistant.course.SourceTextRepository.Piece;
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
    static final String SUMMARISE = SourceSummariser.SUMMARISE;
    static final String OUTLINE = "outline";
    static final String LESSON = "lesson";
    static final String QUESTIONS = "questions";
    static final String WAITING_APPROVAL = "waiting_approval";

    /** Tries per step: the first ask and two retries (Q24, Q41). */
    public static final int ATTEMPTS = SourceSummariser.ATTEMPTS;
    private static final Pattern KEY = Pattern.compile("c(\\d+)l(\\d+)");
    private static final int LESSON_SOURCE_CHARS = 6000;
    private static final int FIRST_PROGRESS = 15;
    private static final int OUTLINE_PROGRESS = 30;
    private static final int CONTENT_FROM = 35;
    private static final int CONTENT_SPAN = 55;

    private final CourseJobRepository jobs;
    private final SourceTextRepository sources;
    private final SourceSummariser summariser;
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
        // A long source takes many calls: the status line moves with each group instead of only at the end.
        SourceSummariser.Summary summary = summariser.summarise(job.sourceDocumentId(), 1, (done, groups) ->
            jobs.setProgress(step.jobId(), SUMMARISE, Math.max(1, FIRST_PROGRESS * done / groups)));

        return new Result(write(summary.output()), summary.inputHash(), List.of(new NextStep(OUTLINE, "")),
            FIRST_PROGRESS, null);
    }

    private Result outline(StepRow step) {
        JobRow job = jobs.job(step.jobId()).orElseThrow(() -> new GenerationException("Không tìm thấy công việc."));
        JsonNode summaries = output(step.jobId(), SUMMARISE, "");
        JsonNode sections = summaries.path("groups").isArray() && !summaries.path("groups").isEmpty()
            ? summaries.path("groups") : summaries.path("sections");
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
        JsonNode outline = summariser.ask(OUTLINE, user.toString(), GenerationSchemas::outline);
        if (job.courseId() != null && job.revisionId() != null) {
            // The outline goes straight into the draft course, where the author edits it until they approve it.
            // The status is checked inside the write transaction: a stop landing after the model call writes nothing.
            drafts.writeOutline(job.courseId(), job.revisionId(), job.ownerUserId(), outline.path("summary").asText(),
                CourseOutlineMapper.objectives(outline), CourseOutlineMapper.chapters(outline),
                running(step.jobId()));
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
        JsonNode content = withSourceNotesInside(summariser.ask(LESSON, user.toString(), GenerationSchemas::lesson),
            pieces);
        if (inCourse) {
            // A job stopped or cancelled while the model was writing leaves the lesson empty: the status is checked
            // inside the write transaction, after the course is locked, so a stop cannot land between check and write.
            drafts.writeLesson(job.courseId(), job.revisionId(), place[0], place[1], blocksOf(content),
                job.ownerUserId(), running(step.jobId()));
        }

        return new Result(write(content), digest(user.toString()), List.of(), contentProgress(step.jobId()), null);
    }

    private BooleanSupplier running(long jobId) {
        return () -> CourseJobRepository.RUNNING.equals(jobs.job(jobId).map(JobRow::status).orElse(null));
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
        JsonNode proposed = summariser.ask(QUESTIONS, user.toString(), GenerationSchemas::questions);
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
                List.of(), steps, null, null, false));
        }

        return blocks;
    }

    /** The lesson with only the source notes that point inside the pages and lines of the pieces the model was given. */
    JsonNode withSourceNotesInside(JsonNode lesson, List<Piece> given) {
        ObjectNode copy = ((ObjectNode) lesson).deepCopy();
        ArrayNode kept = copy.putArray("sourceNotes");
        for (JsonNode note : lesson.path("sourceNotes")) {
            if (inside(note, given)) {
                kept.add(note);
            }
        }

        return copy;
    }

    /** A note that names no page or line claims nothing to check; one that names them must match a given piece. */
    private static boolean inside(JsonNode note, List<Piece> given) {
        boolean named = note.has("page") || note.has("lineStart") || note.has("lineEnd");
        if (!named) {
            return true;
        }
        boolean hasPage = note.path("page").isInt();
        boolean hasLines = note.path("lineStart").isInt() && note.path("lineEnd").isInt();
        if ((note.has("page") && !hasPage) || ((note.has("lineStart") || note.has("lineEnd")) && !hasLines)) {
            return false;
        }
        for (Piece piece : given) {
            boolean pageOk = !hasPage || (piece.page() != null && piece.page() == note.path("page").asInt());
            boolean linesOk = !hasLines || (note.path("lineStart").asInt() >= piece.lineStart()
                && note.path("lineEnd").asInt() <= piece.lineEnd()
                && note.path("lineStart").asInt() <= note.path("lineEnd").asInt());
            if (pageOk && linesOk) {
                return true;
            }
        }

        return false;
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

    private static String join(List<Piece> pieces) {
        return SourceSummariser.join(pieces);
    }

    private static String digest(String text) {
        return SourceSummariser.digest(text);
    }
}
