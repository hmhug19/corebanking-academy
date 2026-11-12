package vn.com.coreacademy.assistant.course;

import java.time.Duration;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.course.CourseJobRepository.DocumentInfo;
import vn.com.coreacademy.assistant.course.CourseJobRepository.JobRow;
import vn.com.coreacademy.assistant.course.CourseJobViews.Inputs;
import vn.com.coreacademy.assistant.course.CourseJobViews.JobSummary;
import vn.com.coreacademy.assistant.course.CourseJobViews.JobView;
import vn.com.coreacademy.assistant.course.CourseJobViews.SourceView;
import vn.com.coreacademy.assistant.course.CourseJobViews.StepView;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentService;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseDetail;
import vn.com.coreacademy.learning.dto.LearningRequests.CreateCourse;
import vn.com.coreacademy.learning.dto.LearningRequests.DraftContent;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;
import vn.com.coreacademy.learning.service.LearningAiDraftService;
import vn.com.coreacademy.learning.service.LearningContentService;
import vn.com.coreacademy.security.SecurityContextUtils;

/**
 * Starts, shows, cancels and deletes the caller's course generation jobs. Only roles that manage courses may use it,
 * the owner unit is decided here (an instructor's own unit, an admin's choice checked against their scope) and never by
 * the model, and a user starts at most {@code jobs-per-day} jobs in any 24 hours. The model work happens later in
 * {@link CourseGenerationWorker}.
 */
@Service
@RequiredArgsConstructor
public class CourseJobService {
    static final String FIRST_STEP = "summarise_sections";
    private static final Duration DAY = Duration.ofHours(24);
    private static final Set<String> LEVELS = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    private static final Set<String> MANAGERS = Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN, RoleCodes.INSTRUCTOR);
    private static final Set<String> ADMINS = Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN);
    private static final int LIST_LIMIT = 50;
    private static final String DEFAULT_TITLE = "Khóa học mới (AI)";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Map<String, String> COURSE_LEVELS = Map.of("BEGINNER", "basic",
        "INTERMEDIATE", "intermediate", "ADVANCED", "advanced");

    private final CourseJobRepository jobs;
    private final KnowledgeDocumentService documents;
    private final LearningAccessPolicy access;
    private final GenerationProperties properties;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final LearningContentService courses;
    private final LearningAiDraftService draftWriter;

    /**
     * Checks who may start a job and for which unit and the day's limit, stores the file as a course source
     * (a document only its uploader reads, never listed or searched; the ingestion worker reads it like any other), and queues the job with its first step.
     */
    public JobView create(MultipartFile file, Inputs requested) {
        Prepared prepared = prepare(requested);
        long documentId = documents.upload(file, DocumentScope.COURSE_SOURCE).id();
        try {
            return queue(prepared, documentId);
        } catch (RuntimeException exception) {
            documents.delete(documentId);
            throw exception;
        }
    }

    /**
     * The same job, for a file the caller already uploaded as their own personal document (the chat composer does that
     * first). The document must be theirs and not used by another job; the same checks and limits apply.
     */
    public JobView createFromDocument(long documentId, Inputs requested) {
        Prepared prepared = prepare(requested);
        if (!jobs.isFreePersonalDocument(documentId, prepared.userId())) {
            throw new BusinessException(ResponseCode.RESOURCE_NOT_FOUND);
        }

        return queue(prepared, documentId);
    }

    private record Prepared(String userId, long ownerOrgId, Inputs inputs) {
    }

    private Prepared prepare(Inputs requested) {
        Actor actor = access.actor();
        requireManager(actor);
        long ownerOrgId = ownerOrg(actor, requested);
        Inputs inputs = checked(requested, ownerOrgId);
        String userId = SecurityContextUtils.getUserId();
        requireWithinDailyLimit(userId, jobs.createdSince(userId, OffsetDateTime.now().minus(DAY)));

        return new Prepared(userId, ownerOrgId, inputs);
    }

    private JobView queue(Prepared prepared, long documentId) {
        // The draft course and its first revision are made with the job, in one transaction: when either fails, neither
        // exists. The course service decides again whether the caller may create it for that unit.
        long jobId = transaction.execute(status -> {
            jobs.lockOwner(prepared.userId());
            requireWithinDailyLimit(prepared.userId(),
                jobs.createdSince(prepared.userId(), OffsetDateTime.now().minus(DAY)));
            Inputs inputs = prepared.inputs();
            String title = inputs.title() == null || inputs.title().isBlank() ? DEFAULT_TITLE : inputs.title().strip();
            CourseDetail course = courses.create(new CreateCourse(prepared.ownerOrgId(), null, newCode(), title,
                new DraftContent(inputs.programmeId(), null, title,
                    inputs.description() == null ? "" : inputs.description().strip(), COURSE_LEVELS.get(inputs.level()),
                    "mandatory", null, null, null, List.of(), List.of(), 0)));
            jobs.markAiGenerated(course.revision().id());
            long created = jobs.insertJob(prepared.userId(), prepared.ownerOrgId(), documentId, write(inputs),
                course.course().id(), course.revision().id());
            jobs.insertStep(created, FIRST_STEP, "");
            return created;
        });

        return get(jobId);
    }

    public JobView get(long jobId) {
        return view(owned(jobId));
    }

    /** The caller's jobs, newest first. */
    public List<JobSummary> list() {
        List<JobRow> rows = jobs.jobsOf(SecurityContextUtils.getUserId(), LIST_LIMIT);
        Map<Long, DocumentInfo> sources = jobs.documents(rows.stream().map(JobRow::sourceDocumentId)
            .filter(java.util.Objects::nonNull).toList());

        return rows.stream().map(row -> new JobSummary(row.id(), row.status(), row.step(), row.progress(),
            row.errorMessage(), name(sources, row), row.createdDraftId(), row.createTime(), row.courseId())).toList();
    }

    /** Stops a job that has not finished; what was generated so far stays visible, nothing more is generated. */
    public JobView cancel(long jobId) {
        JobRow job = owned(jobId);
        if (!jobs.setStatus(job.id(), CourseJobRepository.CANCELLED, null)) {
            throw BusinessException.validation("jobId", "JOB_FINISHED", "Công việc này đã kết thúc.");
        }

        return get(jobId);
    }

    /**
     * Stops a job that has not finished: lessons already written stay, the one being written and the ones not started
     * are left empty and {@code pending} for the author to fill by hand. The structure is unlocked.
     */
    public JobView stop(long jobId) {
        JobRow job = owned(jobId);
        boolean stopped = Boolean.TRUE.equals(transaction.execute(status -> {
            if (!jobs.setStatus(job.id(), CourseJobRepository.STOPPED, null)) {
                return false;
            }
            if (job.courseId() != null && job.revisionId() != null) {
                draftWriter.revertWriting(job.courseId(), job.revisionId(), job.ownerUserId());
            }
            return true;
        }));
        if (!stopped) {
            throw BusinessException.validation("jobId", "JOB_FINISHED", "Công việc này đã kết thúc.");
        }

        return get(jobId);
    }

    /** Deletes the job, its steps and its source document. A job that is still running must be cancelled first. */
    public void delete(long jobId) {
        JobRow job = owned(jobId);
        if (CourseJobRepository.RUNNING.equals(job.status())) {
            throw BusinessException.validation("jobId", "JOB_RUNNING", "Hãy hủy công việc đang chạy trước khi xóa.");
        }
        if (job.sourceDocumentId() != null) {
            documents.delete(job.sourceDocumentId());
        }
        jobs.deleteJob(job.id());
    }

    // ---- checks ----------------------------------------------------------------------------------------------------

    private static void requireManager(Actor actor) {
        if (actor.grants().stream().noneMatch(grant -> MANAGERS.contains(grant.roleCode()))) {
            throw new AccessDeniedException("Only roles that manage courses may generate a course");
        }
    }

    /** An instructor's unit is their own; an admin names one, and it must be inside the admin's scope. */
    private long ownerOrg(Actor actor, Inputs requested) {
        boolean admin = actor.grants().stream().anyMatch(grant -> ADMINS.contains(grant.roleCode()));
        Long own = actor.person().orgId();
        if (!admin) {
            if (own == null) {
                throw BusinessException.validation("ownerOrgId", "NO_UNIT", "Tài khoản chưa thuộc đơn vị nào.");
            }
            if (requested.ownerOrgId() != null && !requested.ownerOrgId().equals(own)) {
                throw new AccessDeniedException("An instructor generates courses for their own unit only");
            }
            return own;
        }
        if (requested.ownerOrgId() == null) {
            throw BusinessException.validation("ownerOrgId", "REQUIRED", "Hãy chọn đơn vị sở hữu khóa học.");
        }
        access.requireManage(actor, requested.ownerOrgId(), null, null);

        return requested.ownerOrgId();
    }

    private Inputs checked(Inputs in, long ownerOrgId) {
        if (in == null) {
            throw BusinessException.validation("inputs", "REQUIRED", "Thiếu thông tin khởi tạo.");
        }
        String learners = in.targetLearners() == null ? "" : in.targetLearners().strip();
        if (learners.isEmpty() || learners.length() > 500) {
            throw BusinessException.validation("targetLearners", "INVALID_LENGTH", "Đối tượng học viên từ 1 đến 500 ký tự.");
        }
        if (in.level() == null || !LEVELS.contains(in.level())) {
            throw BusinessException.validation("level", "INVALID_VALUE", "Trình độ phải là BEGINNER, INTERMEDIATE hoặc ADVANCED.");
        }
        if (in.chapters() == null && in.durationMinutes() == null) {
            throw BusinessException.validation("chapters", "REQUIRED", "Nhập số chương hoặc thời lượng mong muốn.");
        }
        if (in.chapters() != null && (in.chapters() < 1 || in.chapters() > 20)) {
            throw BusinessException.validation("chapters", "OUT_OF_RANGE", "Số chương từ 1 đến 20.");
        }
        if (in.durationMinutes() != null && (in.durationMinutes() < 15 || in.durationMinutes() > 1200)) {
            throw BusinessException.validation("durationMinutes", "OUT_OF_RANGE", "Thời lượng từ 15 đến 1200 phút.");
        }
        if (in.programmeId() == null || !jobs.programmeExists(in.programmeId())) {
            throw BusinessException.validation("programmeId", "NOT_FOUND", "Chương trình đào tạo không tồn tại.");
        }
        if (in.competencyId() == null || !jobs.competencyExists(in.competencyId())) {
            throw BusinessException.validation("competencyId", "NOT_FOUND", "Năng lực không tồn tại.");
        }

        String title = in.title() == null ? null : in.title().strip();
        if (title != null && title.length() > 255) {
            throw BusinessException.validation("title", "INVALID_LENGTH", "Tên khóa học tối đa 255 ký tự.");
        }
        String description = in.description() == null ? null : in.description().strip();
        if (description != null && description.length() > 30000) {
            throw BusinessException.validation("description", "INVALID_LENGTH", "Mô tả tối đa 30000 ký tự.");
        }

        return new Inputs(learners, in.level(), in.chapters(), in.durationMinutes(), in.programmeId(),
            in.competencyId(), ownerOrgId, title, description);
    }

    private static String newCode() {
        StringBuilder code = new StringBuilder("AI-");
        for (int i = 0; i < 8; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }

        return code.toString();
    }

    /** Refuses the job after the day's allowance, telling the user when the oldest one leaves the 24 hours. */
    private void requireWithinDailyLimit(String userId, List<OffsetDateTime> startedInTheDay) {
        if (startedInTheDay.size() < properties.jobsPerDay()) {
            return;
        }
        Duration wait = Duration.between(OffsetDateTime.now(),
            startedInTheDay.get(startedInTheDay.size() - properties.jobsPerDay()).plus(DAY));
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
        String text = minutes >= 60 ? (minutes + 59) / 60 + " giờ" : minutes + " phút";
        throw BusinessException.field(AssistantResponseCode.JOB_RATE_LIMITED, "limit", "DAILY_LIMIT",
            "Bạn đã tạo " + properties.jobsPerDay() + " khóa học bằng AI trong ngày, thử lại sau " + text + ".");
    }

    JobRow owned(long jobId) {
        // A job of someone else answers the same as a missing one, so an id reveals nothing.
        return jobs.job(jobId).filter(row -> row.ownerUserId().equals(SecurityContextUtils.getUserId()))
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
    }

    // ---- views -----------------------------------------------------------------------------------------------------

    private JobView view(JobRow row) {
        Map<Long, DocumentInfo> sources = row.sourceDocumentId() == null ? Map.of()
            : jobs.documents(List.of(row.sourceDocumentId()));
        DocumentInfo source = sources.get(row.sourceDocumentId());
        List<StepView> steps = jobs.steps(row.id()).stream()
            .map(step -> new StepView(step.kind(), step.itemKey(), step.status(), step.errorMessage())).toList();

        return new JobView(row.id(), row.status(), row.step(), row.progress(), row.errorMessage(), read(row.inputs()),
            row.ownerOrgId(), source == null ? null : new SourceView(source.id(), source.fileName(), source.status()),
            row.createdDraftId(), steps, row.createTime(), row.updateTime(), row.courseId(), row.revisionId());
    }

    private static String name(Map<Long, DocumentInfo> sources, JobRow row) {
        DocumentInfo source = row.sourceDocumentId() == null ? null : sources.get(row.sourceDocumentId());

        return source == null ? null : source.fileName();
    }

    private String write(Inputs inputs) {
        try {
            return json.writeValueAsString(inputs);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Inputs read(String text) {
        try {
            return json.readValue(text, Inputs.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
