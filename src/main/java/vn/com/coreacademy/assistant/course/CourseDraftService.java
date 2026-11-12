package vn.com.coreacademy.assistant.course;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.OptionInput;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.QuestionRequest;
import vn.com.coreacademy.assessment.service.QuestionService;
import vn.com.coreacademy.assistant.course.CourseJobRepository.JobRow;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.course.CourseJobViews.ContentView;
import vn.com.coreacademy.assistant.course.CourseJobViews.Inputs;
import vn.com.coreacademy.assistant.course.CourseJobViews.LessonView;
import vn.com.coreacademy.assistant.course.CourseJobViews.QuestionsView;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.learning.service.LearningAiDraftService;
import vn.com.coreacademy.learning.service.LearningAiDraftService.ChapterState;
import vn.com.coreacademy.learning.service.LearningAiOwnership;

/**
 * What the author does with a generation job after the model has written something: edit and approve the outline,
 * regenerate a lesson, and accept or reject proposed questions. The course itself is written into its draft as the
 * job goes, and an accepted question is banked at once, both by the existing course and question services, as the
 * author, so their access rules decide again; nothing here publishes anything.
 */
@Service
@RequiredArgsConstructor
public class CourseDraftService {
    /** Runs one step may have in total, regenerations and retries included, so a job cannot loop on the model. */
    public static final int MAX_STEP_ATTEMPTS = 6;

    /** The decision of a proposed question that is now in the bank. */
    private static final String ADDED = "added";

    private final CourseJobRepository jobs;
    private final CourseJobService owned;
    private final LearningAiDraftService course;
    private final QuestionService questions;
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
        ObjectNode clean = whitelisted(outline);
        jobs.setOutput(step.id(), write(clean));
        writeIntoCourse(job, clean);

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
        JsonNode outline = outlineFromCourse(job, parse(step.output()));
        jobs.setOutput(step.id(), write(outline));
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
        if (keys.isEmpty()) {
            jobs.setStatus(job.id(), CourseJobRepository.COMPLETED, null);
        }
        jobs.reopen(job.id(), CourseGenerationRunner.LESSON, 35);

        return content(jobId);
    }

    // ---- lessons and questions -------------------------------------------------------------------------------------

    /** Writes one lesson again; the other lessons and every proposed question stay as they are. */
    @Transactional
    public ContentView regenerateLesson(long jobId, String lessonKey) {
        JobRow job = lock(jobId);
        if (job.sourceDocumentId() == null) {
            throw refuse("SOURCE_REQUIRED", "Tệp nguồn đã được xóa theo thời hạn lưu trữ. Hãy đính kèm lại tệp để viết lại bài này.");
        }
        StepRow step = jobs.stepByKey(job.id(), CourseGenerationRunner.LESSON, lessonKey)
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        if (step.attempts() >= MAX_STEP_ATTEMPTS) {
            throw refuse("REGENERATION_LIMIT", "Bài này đã được tạo lại quá nhiều lần, hãy sửa nội dung thủ công.");
        }
        if (!jobs.resetStep(step.id())) {
            throw refuse("LESSON_BUSY", "Bài này đang được viết, hãy chờ xong rồi tạo lại.");
        }
        int[] place = CourseOutlineMapper.position(lessonKey);
        if (place != null && job.courseId() != null) {
            // The author asked for it, so even a lesson they edited goes back to the job: pending, then rewritten.
            course.setLessonState(job.courseId(), job.revisionId(), place[0], place[1], LearningAiOwnership.PENDING,
                job.ownerUserId());
        }
        jobs.reopen(job.id(), CourseGenerationRunner.LESSON, job.progress());
        jobs.resetStepIfDone(job.id(), CourseGenerationRunner.QUESTIONS, lessonKey);

        return content(jobId);
    }

    /** Failed steps run again; the finished ones are kept. */
    @Transactional
    public ContentView retry(long jobId) {
        JobRow job = lock(jobId);
        if (jobs.requeueFailed(job.id(), MAX_STEP_ATTEMPTS) == 0) {
            throw refuse("NOTHING_FAILED", "Không có bước nào bị lỗi.");
        }
        jobs.reopen(job.id(), job.step(), job.progress());

        return content(jobId);
    }

    /**
     * Accepts or rejects one proposed question. Accepting writes it to the question bank at once (marked {@code added},
     * so it is written once however often it is accepted); a rejected one stays out. A question already in the bank
     * cannot be rejected here, it is removed where the bank is managed.
     */
    @Transactional
    public ContentView decide(long jobId, String lessonKey, int index, String decision) {
        if (!List.of("accepted", "rejected", "pending").contains(decision)) {
            throw BusinessException.validation("decision", "INVALID_VALUE", "Chọn accepted, rejected hoặc pending.");
        }
        JobRow job = lock(jobId);
        StepRow step = jobs.stepByKey(job.id(), CourseGenerationRunner.QUESTIONS, lessonKey)
            .filter(row -> row.output() != null)
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        ObjectNode output = (ObjectNode) parse(step.output());
        JsonNode list = output.path("questions");
        if (index < 0 || index >= list.size()) {
            throw new BusinessException(ResponseCode.RESOURCE_NOT_FOUND);
        }
        ObjectNode question = (ObjectNode) list.get(index);
        if (ADDED.equals(question.path("decision").asText())) {
            if ("accepted".equals(decision)) {
                return content(jobId);
            }
            throw refuse("QUESTION_ALREADY_ADDED",
                "Câu hỏi này đã được thêm vào ngân hàng câu hỏi nên không loại được ở đây.");
        }
        if ("accepted".equals(decision)) {
            bank(question, read(job.inputs(), Inputs.class));
        } else {
            question.put("decision", decision);
        }
        jobs.setOutput(step.id(), write(output));

        return content(jobId);
    }

    /** Writes one proposed question to the bank with the programme and competency the author chose; marks it added. */
    private void bank(ObjectNode question, Inputs inputs) {
        List<OptionInput> options = new ArrayList<>();
        question.path("options").forEach(option -> options.add(new OptionInput(null,
            option.path("content").asText(), option.path("correct").asBoolean())));
        questions.create(new QuestionRequest(null, null, inputs.competencyId(), null,
            question.path("type").asText(), question.path("content").asText(),
            question.path("explanation").asText(null), question.path("difficulty").asText("MEDIUM"),
            BigDecimal.ONE, "ACTIVE", options, inputs.programmeId()));
        question.put("decision", ADDED);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The outline as the course has it now (the author may have edited it), keeping the model's objectives per lesson. */
    private JsonNode outlineFromCourse(JobRow job, JsonNode previous) {
        if (job.courseId() == null || job.revisionId() == null) {
            return previous;
        }
        Map<String, JsonNode> objectives = new java.util.HashMap<>();
        for (JsonNode chapter : previous.path("chapters")) {
            for (JsonNode lesson : chapter.path("lessons")) {
                objectives.put(lesson.path("title").asText(), lesson.path("objectives"));
            }
        }
        ObjectNode outline = json.createObjectNode();
        outline.put("title", previous.path("title").asText());
        outline.put("summary", previous.path("summary").asText());
        outline.set("objectives", previous.path("objectives"));
        ArrayNode chapters = outline.putArray("chapters");
        for (ChapterState chapter : course.structure(job.revisionId())) {
            ObjectNode copy = chapters.addObject();
            copy.put("title", chapter.title());
            ArrayNode lessons = copy.putArray("lessons");
            for (var lesson : chapter.lessons()) {
                ObjectNode item = lessons.addObject();
                item.put("title", lesson.title());
                JsonNode had = objectives.get(lesson.title());
                ArrayNode list = item.putArray("objectives");
                if (had != null && had.isArray()) {
                    list.addAll((ArrayNode) had);
                }
                item.put("estimatedMinutes", (int) Math.max(1, Math.min(240, lesson.estimatedSeconds() / 60)));
            }
        }
        List<String> problems = GenerationSchemas.outline(outline);
        if (!problems.isEmpty()) {
            throw BusinessException.validation("outline", "INVALID_OUTLINE", "Dàn ý chưa hợp lệ: " + problems.get(0));
        }

        return outline;
    }

    private void writeIntoCourse(JobRow job, JsonNode outline) {
        if (job.courseId() != null && job.revisionId() != null) {
            course.writeOutline(job.courseId(), job.revisionId(), job.ownerUserId(), outline.path("summary").asText(),
                CourseOutlineMapper.objectives(outline), CourseOutlineMapper.chapters(outline));
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
