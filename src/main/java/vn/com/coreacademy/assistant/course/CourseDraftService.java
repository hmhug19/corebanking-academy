package vn.com.coreacademy.assistant.course;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.OptionInput;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.QuestionRequest;
import vn.com.coreacademy.assessment.service.QuestionService;
import vn.com.coreacademy.assistant.course.CourseJobRepository.JobRow;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.course.CourseJobViews.ContentView;
import vn.com.coreacademy.assistant.course.CourseJobViews.Inputs;
import vn.com.coreacademy.assistant.course.CourseJobViews.JobView;
import vn.com.coreacademy.assistant.course.CourseJobViews.LessonView;
import vn.com.coreacademy.assistant.course.CourseJobViews.QuestionsView;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseDetail;
import vn.com.coreacademy.learning.dto.LearningRequests.BlockInput;
import vn.com.coreacademy.learning.dto.LearningRequests.ChapterInput;
import vn.com.coreacademy.learning.dto.LearningRequests.CreateCourse;
import vn.com.coreacademy.learning.dto.LearningRequests.DraftContent;
import vn.com.coreacademy.learning.dto.LearningRequests.LessonInput;
import vn.com.coreacademy.learning.dto.LearningRequests.StepInput;
import vn.com.coreacademy.learning.service.LearningContentService;

/**
 * What the author does with a generation job after the model has written something: edit and approve the outline,
 * regenerate a lesson, accept or reject proposed questions, and ask for the draft. The draft and the accepted
 * questions are written by the existing course and question services, as the author, so their access rules decide
 * again; nothing here publishes anything.
 */
@Service
@RequiredArgsConstructor
public class CourseDraftService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Map<String, String> LEVELS = Map.of("BEGINNER", "basic", "INTERMEDIATE", "intermediate",
        "ADVANCED", "advanced");

    private final CourseJobRepository jobs;
    private final CourseJobService owned;
    private final LearningContentService courses;
    private final QuestionService questions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    // ---- reading ---------------------------------------------------------------------------------------------------

    public ContentView content(long jobId) {
        JobRow job = owned.owned(jobId);
        JsonNode outline = outputOf(job.id(), CourseGenerationRunner.OUTLINE, "");
        List<LessonView> lessons = new ArrayList<>();
        for (StepRow step : jobs.stepsOfKind(job.id(), CourseGenerationRunner.LESSON)) {
            JsonNode content = step.output() == null ? null : parse(step.output());
            lessons.add(new LessonView(step.itemKey(), title(outline, step.itemKey()), step.status(),
                step.errorMessage(), content == null ? null : content.path("blocks"),
                content == null ? null : content.path("sourceNotes")));
        }
        List<QuestionsView> proposed = new ArrayList<>();
        for (StepRow step : jobs.stepsOfKind(job.id(), CourseGenerationRunner.QUESTIONS)) {
            JsonNode content = step.output() == null ? null : parse(step.output());
            proposed.add(new QuestionsView(step.itemKey(), step.status(), step.errorMessage(),
                content == null ? null : content.path("questions")));
        }

        return new ContentView(job.id(), job.status(), job.step(), job.progress(), job.errorMessage(), outline,
            outlineLocked(job.id()), lessons, proposed, job.createdDraftId());
    }

    // ---- outline ---------------------------------------------------------------------------------------------------

    /** Saves the author's edit of the outline; only before approval, and only a complete, valid outline. */
    @Transactional
    public ContentView updateOutline(long jobId, JsonNode outline) {
        JobRow job = lock(jobId);
        StepRow step = outlineStep(job);
        if (outlineLocked(job.id())) {
            throw refuse("OUTLINE_LOCKED", "Dàn ý đã được duyệt nên không sửa được nữa.");
        }
        List<String> problems = GenerationSchemas.outline(outline);
        if (!problems.isEmpty()) {
            throw BusinessException.validation("outline", "INVALID_OUTLINE", "Dàn ý chưa hợp lệ: " + problems.get(0));
        }
        jobs.setOutput(step.id(), write(whitelisted(outline)));

        return content(jobId);
    }

    /** Keeps only the outline fields the draft uses, so a client cannot store extra data in the job. */
    private ObjectNode whitelisted(JsonNode outline) {
        ObjectNode clean = json.createObjectNode();
        clean.put("title", outline.path("title").asText());
        clean.put("summary", outline.path("summary").asText());
        ArrayNode objectives = clean.putArray("objectives");
        outline.path("objectives").forEach(objective -> objectives.add(objective.asText()));
        ArrayNode chapters = clean.putArray("chapters");
        for (JsonNode chapter : outline.path("chapters")) {
            ObjectNode copy = chapters.addObject();
            copy.put("title", chapter.path("title").asText());
            ArrayNode lessons = copy.putArray("lessons");
            for (JsonNode lesson : chapter.path("lessons")) {
                ObjectNode item = lessons.addObject();
                item.put("title", lesson.path("title").asText());
                ArrayNode lessonObjectives = item.putArray("objectives");
                lesson.path("objectives").forEach(objective -> lessonObjectives.add(objective.asText()));
                item.put("estimatedMinutes", lesson.path("estimatedMinutes").asInt());
            }
        }

        return clean;
    }

    /** Approves the outline: from now it is locked and the lessons, then their questions, are generated. */
    @Transactional
    public ContentView approveOutline(long jobId) {
        JobRow job = lock(jobId);
        StepRow step = outlineStep(job);
        if (outlineLocked(job.id())) {
            throw refuse("OUTLINE_LOCKED", "Dàn ý đã được duyệt rồi.");
        }
        JsonNode outline = parse(step.output());
        List<String> keys = new ArrayList<>();
        int chapter = 0;
        for (JsonNode chapterNode : outline.path("chapters")) {
            chapter++;
            for (int lesson = 1; lesson <= chapterNode.path("lessons").size(); lesson++) {
                keys.add("c" + chapter + "l" + lesson);
            }
        }
        keys.forEach(key -> jobs.insertStep(job.id(), CourseGenerationRunner.LESSON, key));
        keys.forEach(key -> jobs.insertStep(job.id(), CourseGenerationRunner.QUESTIONS, key));
        jobs.reopen(job.id(), CourseGenerationRunner.LESSON, 35);

        return content(jobId);
    }

    // ---- lessons and questions -------------------------------------------------------------------------------------

    /** Writes one lesson again; the other lessons and every proposed question stay as they are. */
    @Transactional
    public ContentView regenerateLesson(long jobId, String lessonKey) {
        JobRow job = lock(jobId);
        refuseWhenDrafted(job);
        StepRow step = jobs.stepByKey(job.id(), CourseGenerationRunner.LESSON, lessonKey)
            .orElseThrow(() -> new BusinessException(vn.com.coreacademy.common.exception.ResponseCode.RESOURCE_NOT_FOUND));
        if (!jobs.resetStep(step.id())) {
            throw refuse("LESSON_BUSY", "Bài này đang được viết, hãy chờ xong rồi tạo lại.");
        }
        jobs.reopen(job.id(), CourseGenerationRunner.LESSON, job.progress());

        return content(jobId);
    }

    /** Failed steps run again; the finished ones are kept. */
    @Transactional
    public ContentView retry(long jobId) {
        JobRow job = lock(jobId);
        refuseWhenDrafted(job);
        if (jobs.requeueFailed(job.id()) == 0) {
            throw refuse("NOTHING_FAILED", "Không có bước nào bị lỗi.");
        }
        jobs.reopen(job.id(), job.step(), job.progress());

        return content(jobId);
    }

    /** Accepts or rejects one proposed question; undecided ones never reach the question bank. */
    @Transactional
    public ContentView decide(long jobId, String lessonKey, int index, String decision) {
        if (!List.of("accepted", "rejected", "pending").contains(decision)) {
            throw BusinessException.validation("decision", "INVALID_VALUE", "Chọn accepted, rejected hoặc pending.");
        }
        JobRow job = lock(jobId);
        StepRow step = jobs.stepByKey(job.id(), CourseGenerationRunner.QUESTIONS, lessonKey)
            .filter(row -> row.output() != null)
            .orElseThrow(() -> new BusinessException(vn.com.coreacademy.common.exception.ResponseCode.RESOURCE_NOT_FOUND));
        if (job.createdDraftId() != null) {
            throw refuse("DRAFT_CREATED", "Bản nháp đã được tạo nên không đổi lựa chọn được nữa.");
        }
        ObjectNode output = (ObjectNode) parse(step.output());
        JsonNode list = output.path("questions");
        if (index < 0 || index >= list.size()) {
            throw new BusinessException(vn.com.coreacademy.common.exception.ResponseCode.RESOURCE_NOT_FOUND);
        }
        ((ObjectNode) list.get(index)).put("decision", decision);
        jobs.setOutput(step.id(), write(output));

        return content(jobId);
    }

    // ---- the draft -------------------------------------------------------------------------------------------------

    /**
     * Builds the whole course from the approved outline and the lessons and writes it once through the course service,
     * with the accepted questions going to the bank with the programme and competency the author chose. The revision is
     * marked AI generated in the same transaction. Asking again after it was made returns the same draft.
     */
    @Transactional
    public JobView createDraft(long jobId) {
        JobRow job = lock(jobId);
        if (job.createdDraftId() != null) {
            return owned.get(jobId);
        }
        if (!outlineLocked(job.id())) {
            throw refuse("OUTLINE_NOT_APPROVED", "Hãy duyệt dàn ý trước khi tạo bản nháp.");
        }
        List<StepRow> lessons = jobs.stepsOfKind(job.id(), CourseGenerationRunner.LESSON);
        if (lessons.isEmpty() || lessons.stream().anyMatch(step -> !"done".equals(step.status()))) {
            throw refuse("LESSONS_NOT_READY", "Cần viết xong mọi bài học trước khi tạo bản nháp.");
        }
        Inputs inputs = read(job.inputs(), Inputs.class);
        JsonNode outline = outputOf(job.id(), CourseGenerationRunner.OUTLINE, "");

        List<ChapterInput> chapters = new ArrayList<>();
        int chapterNumber = 0;
        for (JsonNode chapter : outline.path("chapters")) {
            chapterNumber++;
            List<LessonInput> lessonInputs = new ArrayList<>();
            int lessonNumber = 0;
            for (JsonNode lesson : chapter.path("lessons")) {
                lessonNumber++;
                String key = "c" + chapterNumber + "l" + lessonNumber;
                JsonNode written = outputOf(job.id(), CourseGenerationRunner.LESSON, key);
                lessonInputs.add(new LessonInput(lesson.path("title").asText(), "content",
                    BigDecimal.valueOf(lesson.path("estimatedMinutes").asInt(10) * 60L), true, null,
                    blocksOf(written)));
            }
            chapters.add(new ChapterInput(chapter.path("title").asText(), lessonInputs));
        }
        List<String> objectives = new ArrayList<>();
        outline.path("objectives").forEach(objective -> objectives.add(objective.asText()));
        DraftContent draft = new DraftContent(inputs.programmeId(), null, outline.path("title").asText(),
            outline.path("summary").asText(), LEVELS.get(inputs.level()), "mandatory", null, null, null, objectives,
            chapters, 0);

        CourseDetail created = courses.create(new CreateCourse(job.ownerOrgId(), null, newCode(job.id()),
            outline.path("title").asText(), draft));
        jdbc.update("UPDATE learning_hub.course_revisions SET ai_generated = true WHERE revision_id = ?",
            created.revision().id());
        int accepted = writeAcceptedQuestions(job, inputs);
        jobs.setDraft(job.id(), created.course().id());
        jobs.insertStep(job.id(), "assemble", "");
        jobs.stepByKey(job.id(), "assemble", "").ifPresent(step -> {
            jdbc.update("UPDATE learning_hub.course_generation_steps SET status = 'done', finished_at = now(), "
                + "output = ?::jsonb WHERE step_id = ?", write(json.createObjectNode()
                .put("courseId", created.course().id()).put("revisionId", created.revision().id())
                .put("acceptedQuestions", accepted)), step.id());
        });
        jobs.setProgress(job.id(), "assemble", 100);
        jobs.setStatus(job.id(), CourseJobRepository.COMPLETED, null);

        return owned.get(jobId);
    }

    private int writeAcceptedQuestions(JobRow job, Inputs inputs) {
        int accepted = 0;
        for (StepRow step : jobs.stepsOfKind(job.id(), CourseGenerationRunner.QUESTIONS)) {
            if (step.output() == null) {
                continue;
            }
            for (JsonNode question : parse(step.output()).path("questions")) {
                if (!"accepted".equals(question.path("decision").asText())) {
                    continue;
                }
                List<OptionInput> options = new ArrayList<>();
                question.path("options").forEach(option -> options.add(new OptionInput(null,
                    option.path("content").asText(), option.path("correct").asBoolean())));
                questions.create(new QuestionRequest(null, null, inputs.competencyId(), null,
                    question.path("type").asText(), question.path("content").asText(),
                    question.path("explanation").asText(null), question.path("difficulty").asText("MEDIUM"),
                    BigDecimal.ONE, "ACTIVE", options, inputs.programmeId()));
                accepted++;
            }
        }

        return accepted;
    }

    private static List<BlockInput> blocksOf(JsonNode lesson) {
        List<BlockInput> blocks = new ArrayList<>();
        for (JsonNode block : lesson.path("blocks")) {
            boolean process = "process".equals(block.path("type").asText());
            List<StepInput> steps = new ArrayList<>();
            if (process) {
                block.path("steps").forEach(step -> steps.add(new StepInput(step.path("title").asText(),
                    step.path("description").asText(null))));
            }
            blocks.add(new BlockInput(process ? "process" : "text", block.path("title").asText(),
                process ? null : block.path("body").asText(), process ? null : "markdown", "manual", null, true,
                List.of(), steps, null, null));
        }

        return blocks;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void refuseWhenDrafted(JobRow job) {
        if (job.createdDraftId() != null) {
            throw refuse("DRAFT_CREATED", "Bản nháp đã được tạo nên không chạy lại được nữa.");
        }
    }

    private JobRow lock(long jobId) {
        owned.owned(jobId);
        JobRow job = jobs.lockJob(jobId).orElseThrow();
        if (CourseJobRepository.CANCELLED.equals(job.status())) {
            throw refuse("JOB_CANCELLED", "Công việc đã bị hủy.");
        }

        return job;
    }

    private StepRow outlineStep(JobRow job) {
        return jobs.stepByKey(job.id(), CourseGenerationRunner.OUTLINE, "")
            .filter(step -> "done".equals(step.status()) && step.output() != null)
            .orElseThrow(() -> refuse("OUTLINE_NOT_READY", "Dàn ý chưa được tạo xong."));
    }

    private boolean outlineLocked(long jobId) {
        return jobs.countSteps(jobId, CourseGenerationRunner.LESSON, null) > 0;
    }

    private JsonNode outputOf(long jobId, String kind, String key) {
        return jobs.stepByKey(jobId, kind, key).filter(step -> step.output() != null)
            .map(step -> parse(step.output())).orElse(null);
    }

    private static String title(JsonNode outline, String key) {
        try {
            return CourseGenerationRunner.lessonOf(outline, key).path("title").asText();
        } catch (RuntimeException exception) {
            return key;
        }
    }

    private static BusinessException refuse(String code, String message) {
        return BusinessException.validation("jobId", code, message);
    }

    private static String newCode(long jobId) {
        StringBuilder code = new StringBuilder("AI").append(jobId).append('-');
        for (int i = 0; i < 5; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }

        return code.toString();
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
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

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
