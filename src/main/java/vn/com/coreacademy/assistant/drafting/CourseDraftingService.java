package vn.com.coreacademy.assistant.drafting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizType;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ReviewPolicy;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoreMethod;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoringMode;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.SelectionMode;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.OptionInput;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.PoolItem;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.PoolRequest;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.QuestionRequest;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.QuizRequest;
import vn.com.coreacademy.assessment.dto.CourseExamRequest;
import vn.com.coreacademy.assessment.service.CourseExamService;
import vn.com.coreacademy.assessment.service.QuestionService;
import vn.com.coreacademy.assistant.course.CourseDraftService;
import vn.com.coreacademy.assistant.course.GenerationAllowance;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;
import vn.com.coreacademy.assistant.drafting.DraftingViews.BasicsConfirm;
import vn.com.coreacademy.assistant.drafting.DraftingViews.DraftQuestion;
import vn.com.coreacademy.assistant.drafting.DraftingViews.OutlineConfirm;
import vn.com.coreacademy.assistant.drafting.DraftingViews.RulesConfirm;
import vn.com.coreacademy.assistant.drafting.DraftingViews.SessionView;
import vn.com.coreacademy.assistant.drafting.DraftingViews.SourceView;
import vn.com.coreacademy.assistant.drafting.DraftingViews.StartFields;
import vn.com.coreacademy.assistant.drafting.DraftingViews.StepView;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentService;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseDetail;
import vn.com.coreacademy.learning.dto.LearningRequests.DraftRequest;
import vn.com.coreacademy.learning.dto.LearningViews.CourseLessonView;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;
import vn.com.coreacademy.learning.service.LearningContentService;
import vn.com.coreacademy.security.SecurityContextUtils;

/**
 * Soạn cùng trợ lý on the server: starts a drafting session from a document and shows it. Only roles that manage
 * courses may use it; the owner unit is decided here (an instructor's own unit, an admin's choice checked against their
 * scope), never by the model; and every start counts against the daily course generation allowance. The model work
 * happens later in {@link CourseDraftingWorker}, so closing the chat or the page does not stop it. Confirming step 1
 * creates the draft course through the course service's own create path, so its checks (scope, code) apply as on the
 * page.
 */
@Service
@RequiredArgsConstructor
public class CourseDraftingService {
    private static final Set<String> MANAGERS = Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN, RoleCodes.INSTRUCTOR);
    /** Where a confirmed outline keeps its lesson notes in the step's proposal. */
    static final String LESSON_NOTES = "lessonNotes";
    private static final Set<String> ADMINS = Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN);

    private final CourseDraftingRepository drafting;
    private final KnowledgeDocumentService documents;
    private final LearningAccessPolicy access;
    private final GenerationAllowance allowance;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final LearningContentService courses;
    private final Validator validator;
    private final QuestionService questions;
    private final CourseExamService exams;
    private final LearningIdentityService identity;

    private record Prepared(String userId, long ownerOrgId, StartFields fields, Long conversationId) {
    }

    /**
     * Checks who may start and for which unit, the fields and the day's allowance; stores the file as a course source
     * (only its uploader reads it, it is never listed or searched) and queues step 1. The document is deleted again
     * when the session cannot be queued.
     */
    public SessionView start(MultipartFile file, StartFields fields, Long conversationId) {
        String userId = SecurityContextUtils.getUserId();
        Prepared prepared = prepare(identity.actor(userId), userId, fields == null ? StartFields.NONE : fields,
            conversationId);
        long documentId = documents.upload(file, DocumentScope.COURSE_SOURCE, null).id();
        try {
            return queue(prepared, documentId);
        } catch (RuntimeException exception) {
            documents.delete(documentId);
            throw exception;
        }
    }

    /**
     * The chat's file, uploaded before the turn that hands it to {@link #startFromSource}: stored as the caller's
     * course source (only they read it, it is never listed or searched) and linked to nothing yet. Only roles that
     * manage courses may upload one.
     */
    public SourceView uploadSource(MultipartFile file) {
        requireManager(access.actor());
        var uploaded = documents.upload(file, DocumentScope.COURSE_SOURCE, null);

        return new SourceView(uploaded.id(), uploaded.fileName(), uploaded.status());
    }

    /**
     * Starts a session from a document the chat already uploaded ({@code start_course_drafting}); step messages go to
     * {@code conversationId}. It runs on the chat stream's thread, so the caller is {@code userId} from the turn's own
     * context, never the security context. The document must be {@code userId}'s own course source that no session or
     * job uses yet; any other id is refused as missing. The same checks as {@link #start} apply.
     */
    public SessionView startFromSource(String userId, long documentId, StartFields hints, Long conversationId) {
        Prepared prepared = prepare(identity.actor(userId), userId, hints == null ? StartFields.NONE : hints,
            conversationId);
        if (!drafting.freeSourceOf(documentId, userId)) {
            throw BusinessException.validation("documentId", "NOT_FOUND", "Không tìm thấy tài liệu đính kèm.");
        }

        return queue(prepared, documentId);
    }

    public SessionView get(long sessionId) {
        requireManager(access.actor());
        return view(owned(sessionId));
    }

    /** The caller's latest session of the course, so its edit page resumes at the right step; null when it has none. */
    public SessionView forCourse(long courseId) {
        requireManager(access.actor());
        return drafting.latestForCourse(courseId, SecurityContextUtils.getUserId()).map(this::view).orElse(null);
    }

    /**
     * Chốt bước: the step is saved with what is on the page now ({@code body}) and the next one is queued. Only the
     * current step, once proposed (or failed, or filled by hand), may be confirmed; confirming step 1 again after it
     * created the draft returns the session as it is.
     */
    public SessionView confirm(long sessionId, int step, JsonNode body) {
        requireManager(access.actor());
        owned(sessionId);
        return switch (step) {
            case 1 -> confirmBasicsStep(sessionId, body);
            case 2 -> {
                OutlineConfirm outline = checked(body, OutlineConfirm.class, "draft");
                yield view(transaction.execute(status -> confirmOutline(sessionId, outline)));
            }
            case 3 -> {
                RulesConfirm rules = checked(body, RulesConfirm.class, "draft");
                yield view(transaction.execute(status -> confirmRules(sessionId, rules)));
            }
            case DraftingStates.LAST_STEP -> view(transaction.execute(status -> confirmAudience(sessionId)));
            default -> throw notReady();
        };
    }

    private SessionView confirmBasicsStep(long sessionId, JsonNode body) {
        int step = DraftingStates.FIRST_STEP;
        BasicsConfirm basics = checked(body, BasicsConfirm.class, "course");
        try {
            return view(transaction.execute(status -> confirmBasics(sessionId, basics)));
        } catch (BusinessException exception) {
            if (exception.getResponseCode() != LearningResponseCode.DUPLICATE) {
                throw exception;
            }
            // The create rolled back: the new code is proposed in a transaction of its own.
            String taken = basics.course().code().strip();
            String code = CourseDraftingProposer.freeCode(drafting);
            transaction.executeWithoutResult(status -> drafting.proposeCode(sessionId, step, code,
                "Mã " + taken + " đã có; trợ lý đề xuất mã " + code + "."));
            throw BusinessException.field(LearningResponseCode.DUPLICATE, "course.code", "DUPLICATE",
                "Mã khóa học đã có. Trợ lý đã đề xuất mã mới.");
        }
    }

    private SessionRow confirmBasics(long sessionId, BasicsConfirm basics) {
        SessionRow session = drafting.lockSession(sessionId).orElseThrow();
        if (session.courseId() != null) {
            return session;
        }
        if (!DraftingStates.ACTIVE.equals(session.status()) || session.currentStep() != DraftingStates.FIRST_STEP
            || !confirmable(stepRow(sessionId, DraftingStates.FIRST_STEP))) {
            throw notReady();
        }
        CourseDetail created = courses.create(basics.course());
        int next = DraftingStates.FIRST_STEP + 1;
        drafting.linkCourse(sessionId, created.course().id(), created.revision().id(), next);
        drafting.confirmStep(sessionId, DraftingStates.FIRST_STEP);
        drafting.insertStep(sessionId, next);

        return drafting.session(sessionId).orElseThrow();
    }

    /**
     * Chốt bước 2: the page's draft is saved through the course service, so its checks apply as with "Lưu nháp". The
     * assistant never writes lesson content, so a proposed lesson arriving with content is refused; lessons left empty
     * never block the step (only publishing checks content). With a proposal in it the revision is marked as AI made,
     * so publishing asks the author to confirm the review; each proposed lesson's description and source stay on the
     * session by saved lesson id (lessons have no description of their own). Step 3 is queued.
     */
    private SessionRow confirmOutline(long sessionId, OutlineConfirm outline) {
        int step = DraftingStates.FIRST_STEP + 1;
        SessionRow session = drafting.lockSession(sessionId).orElseThrow();
        DraftingStepRow row = stepRow(sessionId, step);
        if (!DraftingStates.ACTIVE.equals(session.status()) || session.currentStep() != step
            || session.courseId() == null || !confirmable(row)) {
            throw notReady();
        }
        JsonNode proposal = row.proposal() == null ? null : read(row.proposal(), new TypeReference<JsonNode>() { });
        List<JsonNode> proposed = proposedLessons(proposal);
        requireNoContentIn(proposed, outline.draft());

        CourseDetail saved = courses.saveDraft(session.courseId(), session.revisionId(), outline.draft());
        if (!proposed.isEmpty()) {
            drafting.markAiGenerated(session.revisionId());
        }
        drafting.confirmOutline(sessionId, step, write(lessonNotes(proposal, proposed, saved)));
        drafting.advance(sessionId, step + 1);
        drafting.insertStep(sessionId, step + 1);

        return drafting.session(sessionId).orElseThrow();
    }

    /**
     * Chốt bước 3, all or nothing: the page's draft is saved (completion rules, bonus points), the kept questions are
     * created in the course's question bank with the chosen competency, and a new fixed final exam of exactly those
     * questions is created and selected to complete the course, through the same exam service as the page. Questions
     * the author removed are never created; before this they lived only in the session. Kept proposals mark the
     * revision as AI made. Step 4 is queued.
     */
    private SessionRow confirmRules(long sessionId, RulesConfirm rules) {
        int step = DraftingStates.FIRST_STEP + 2;
        SessionRow session = drafting.lockSession(sessionId).orElseThrow();
        DraftingStepRow row = stepRow(sessionId, step);
        if (!DraftingStates.ACTIVE.equals(session.status()) || session.currentStep() != step
            || session.courseId() == null || !confirmable(row)) {
            throw notReady();
        }
        DraftRequest draft = rules.draft();
        List<DraftQuestion> kept = rules.questions();
        if (!kept.isEmpty() && rules.competencyId() == null) {
            throw BusinessException.validation("competencyId", "REQUIRED",
                "Chọn năng lực cho các câu hỏi trước khi chốt bước này.");
        }
        if (!kept.isEmpty() && draft.programId() == null) {
            throw BusinessException.validation("draft.programId", "REQUIRED",
                "Chọn chương trình đào tạo để tạo bài kiểm tra từ các câu hỏi.");
        }

        CourseDetail saved = courses.saveDraft(session.courseId(), session.revisionId(), draft);
        if (!kept.isEmpty()) {
            // The bank of a course is its saved programme's, so the draft is saved before the questions are created.
            List<PoolItem> pool = new ArrayList<>();
            for (DraftQuestion question : kept) {
                long questionId = questions.create(new QuestionRequest(session.courseId(), null,
                    rules.competencyId(), null, question.type(), question.content().strip(),
                    blankToNull(question.explanation()), question.difficulty(), BigDecimal.ONE, ItemStatus.ACTIVE,
                    question.options().stream().map(option -> new OptionInput(null, option.content().strip(),
                        option.correct())).toList())).id();
                pool.add(new PoolItem(questionId, pool.size(), null));
            }
            exams.save(new CourseExamRequest(session.courseId(), session.revisionId(), null,
                withVersion(draft, saved.revision().rowVersion()), null, null,
                finalExam(session.courseId(), draft.title(), pool.size()), new PoolRequest(pool)));
            if (row.proposal() != null) {
                drafting.markAiGenerated(session.revisionId());
            }
        }
        drafting.confirmStep(sessionId, step);
        drafting.advance(sessionId, step + 1);
        drafting.insertStep(sessionId, step + 1);

        return drafting.session(sessionId).orElseThrow();
    }

    /**
     * Chốt bước 4 ends the flow and stores nothing on the course: the proposed targets only filled the page's assign
     * form, and assigning, sharing and publishing stay the author's own actions (publishing still asks to confirm the
     * review of AI content). The body is ignored: whatever the page holds for step 4 is saved by publishing, not here.
     */
    private SessionRow confirmAudience(long sessionId) {
        int step = DraftingStates.LAST_STEP;
        SessionRow session = drafting.lockSession(sessionId).orElseThrow();
        if (!DraftingStates.ACTIVE.equals(session.status()) || session.currentStep() != step
            || session.courseId() == null || !confirmable(stepRow(sessionId, step))) {
            throw notReady();
        }
        drafting.confirmStep(sessionId, step);
        drafting.finish(sessionId);

        return drafting.session(sessionId).orElseThrow();
    }

    /**
     * "Đề xuất lại" (and "Thử lại" on a failed step): the step is queued again with its last proposal dropped; the
     * other steps, the session's current step and the draft course stay as they are, and the page writes the new
     * proposal into that step's fields only. A step being proposed or filled by hand, or one of a session that is
     * over, cannot be. It counts against the daily course generation allowance, and a step that has used every attempt
     * is left to the author. A failed session is active again.
     */
    public SessionView proposeAgain(long sessionId, int step) {
        requireManager(access.actor());
        owned(sessionId);
        String userId = SecurityContextUtils.getUserId();
        return view(transaction.execute(status -> {
            SessionRow session = drafting.lockSession(sessionId).orElseThrow();
            DraftingStepRow row = stepRow(sessionId, step);
            boolean open = DraftingStates.ACTIVE.equals(session.status())
                || DraftingStates.FAILED_SESSION.equals(session.status());
            if (!open || row == null || !Set.of(DraftingStates.PROPOSED, DraftingStates.CONFIRMED,
                DraftingStates.FAILED).contains(row.state())) {
                throw BusinessException.validation("step", "INVALID_STATE",
                    "Bước này không đề xuất lại được lúc này.");
            }
            if (row.attempts() >= CourseDraftService.MAX_STEP_ATTEMPTS) {
                throw BusinessException.validation("step", "REPROPOSE_LIMIT",
                    "Bước này đã được đề xuất lại quá nhiều lần, hãy tự điền.");
            }
            allowance.lock(userId);
            allowance.require(userId);
            drafting.reopenStep(sessionId, step);
            drafting.recordReproposal(sessionId, step, userId);
            drafting.reactivate(sessionId);
            return drafting.session(sessionId).orElseThrow();
        }));
    }

    /**
     * "Dừng": whatever the assistant is proposing stops. A step that still holds a proposal keeps it, one with nothing
     * yet is the author's to fill (and confirm as usual); the session goes on. What a worker still brings for it is
     * dropped. With nothing being proposed it changes nothing.
     */
    public SessionView stop(long sessionId) {
        requireManager(access.actor());
        owned(sessionId);
        return view(transaction.execute(status -> {
            drafting.lockSession(sessionId).orElseThrow();
            drafting.stopProposing(sessionId);
            return drafting.session(sessionId).orElseThrow();
        }));
    }

    /**
     * "Tự điền": the current step is the author's to fill and the assistant's part of the session ends there, so no
     * proposal arrives for it any more (the worker takes no step of a session that is not active, and drops what it
     * was still writing). A confirmed step, a step not reached or a finished session cannot be.
     */
    public SessionView manual(long sessionId, int step) {
        requireManager(access.actor());
        owned(sessionId);
        return view(transaction.execute(status -> {
            SessionRow session = drafting.lockSession(sessionId).orElseThrow();
            boolean open = DraftingStates.ACTIVE.equals(session.status())
                || DraftingStates.FAILED_SESSION.equals(session.status());
            if (!open || session.currentStep() != step || !drafting.manualStep(sessionId, step)) {
                throw BusinessException.validation("step", "INVALID_STATE", "Bước này không tự điền được lúc này.");
            }
            drafting.endAsManual(sessionId);
            return drafting.session(sessionId).orElseThrow();
        }));
    }

    /** The exam the page would create with its defaults, fixed to the kept questions: 15 minutes, 70 %, 3 attempts. */
    private static QuizRequest finalExam(long courseId, String courseTitle, int questionCount) {
        String title = courseTitle == null || courseTitle.isBlank() ? "Thi cuối khóa"
            : "Thi cuối khóa: " + courseTitle.strip();
        return new QuizRequest(courseId, title.substring(0, Math.min(title.length(), 255)), null, QuizType.FINAL_EXAM,
            SelectionMode.FIXED, questionCount, true, true, 15, null, null, 0, BigDecimal.valueOf(70),
            ScoringMode.ALL_OR_NOTHING, ScoreMethod.BEST, ReviewPolicy.AFTER_SUBMIT, 3, 0, false, true);
    }

    private static DraftRequest withVersion(DraftRequest draft, long version) {
        return new DraftRequest(version, draft.programId(), draft.moduleId(), draft.title(), draft.summary(),
            draft.level(), draft.defaultRequirement(), draft.coverAssetId(), draft.coverIcon(), draft.coverColor(),
            draft.objectives(), draft.chapters(), draft.rewardPoints(), draft.finalQuizId());
    }

    private static List<JsonNode> proposedLessons(JsonNode proposal) {
        List<JsonNode> lessons = new ArrayList<>();
        if (proposal != null) {
            proposal.path("chapters").forEach(chapter -> chapter.path("lessons").forEach(lessons::add));
        }

        return lessons;
    }

    private static void requireNoContentIn(List<JsonNode> proposed, DraftRequest draft) {
        Set<String> titles = new HashSet<>();
        proposed.forEach(lesson -> titles.add(lesson.path("title").asText().strip()));
        boolean filled = draft.chapters().stream().flatMap(chapter -> chapter.lessons().stream())
            .anyMatch(lesson -> lesson.title() != null && titles.contains(lesson.title().strip())
                && !lesson.blocks().isEmpty());
        if (filled) {
            throw BusinessException.validation("draft.chapters", "CONTENT_NOT_EMPTY",
                "Bài do trợ lý đề xuất được lưu với nội dung trống; hãy thêm nội dung sau khi chốt bước này.");
        }
    }

    /**
     * Each saved lesson that came from the proposal, with the proposal's description and source section: matched by
     * title first, then, for a lesson the author renamed, by its place in the outline. Lessons the author added get
     * none.
     */
    private ArrayNode lessonNotes(JsonNode proposal, List<JsonNode> proposed, CourseDetail saved) {
        ArrayNode notes = json.createArrayNode();
        if (proposal == null) {
            return notes;
        }
        List<List<JsonNode>> byPlace = new ArrayList<>();
        proposal.path("chapters").forEach(chapter -> {
            List<JsonNode> lessons = new ArrayList<>();
            chapter.path("lessons").forEach(lessons::add);
            byPlace.add(lessons);
        });
        Set<JsonNode> used = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<Long, JsonNode> matched = new HashMap<>();
        List<CourseLessonView> savedLessons = new ArrayList<>();
        saved.chapters().forEach(chapter -> chapter.lessons().forEach(lesson -> savedLessons.add(lesson.lesson())));
        for (CourseLessonView lesson : savedLessons) {
            proposed.stream().filter(candidate -> !used.contains(candidate))
                .filter(candidate -> candidate.path("title").asText().strip().equals(lesson.title().strip()))
                .findFirst().ifPresent(candidate -> {
                    used.add(candidate);
                    matched.put(lesson.id(), candidate);
                });
        }
        for (int c = 0; c < saved.chapters().size() && c < byPlace.size(); c++) {
            var lessons = saved.chapters().get(c).lessons();
            for (int l = 0; l < lessons.size() && l < byPlace.get(c).size(); l++) {
                JsonNode candidate = byPlace.get(c).get(l);
                long lessonId = lessons.get(l).lesson().id();
                if (!matched.containsKey(lessonId) && !used.contains(candidate)) {
                    used.add(candidate);
                    matched.put(lessonId, candidate);
                }
            }
        }
        for (CourseLessonView lesson : savedLessons) {
            JsonNode from = matched.get(lesson.id());
            if (from != null) {
                notes.addObject().put("lessonId", lesson.id())
                    .put("description", from.path("description").asText())
                    .put("sourceSection", from.path("sourceSection").asText());
            }
        }

        return notes;
    }

    private DraftingStepRow stepRow(long sessionId, int step) {
        return drafting.steps(sessionId).stream().filter(row -> row.stepNo() == step).findFirst().orElse(null);
    }

    private static boolean confirmable(DraftingStepRow step) {
        return step != null && Set.of(DraftingStates.PROPOSED, DraftingStates.FAILED, DraftingStates.MANUAL)
            .contains(step.state());
    }

    private static BusinessException notReady() {
        return BusinessException.validation("step", "INVALID_STATE", "Bước này chưa sẵn sàng để chốt.");
    }

    /** A confirm body, checked with the course rules of what it carries; errors name the field under {@code field}. */
    private <T> T checked(JsonNode body, Class<T> type, String field) {
        T value;
        try {
            value = json.treeToValue(body == null ? json.createObjectNode() : body, type);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw BusinessException.validation(field, "INVALID_FORMAT", "Kiểu dữ liệu hoặc giá trị không hợp lệ.");
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }

        return value;
    }

    private Prepared prepare(Actor actor, String userId, StartFields requested, Long conversationId) {
        requireManager(actor);
        long ownerOrgId = ownerOrg(actor, requested.ownerOrgId());
        StartFields fields = checked(requested, ownerOrgId);
        if (conversationId != null && !drafting.conversationOwnedBy(conversationId, userId)) {
            // Step messages go to this conversation: someone else's must not receive them.
            throw BusinessException.validation("conversationId", "NOT_FOUND", "Không tìm thấy hội thoại.");
        }
        allowance.require(userId);

        return new Prepared(userId, ownerOrgId, fields, conversationId);
    }

    private SessionView queue(Prepared prepared, long documentId) {
        long sessionId = transaction.execute(status -> {
            allowance.lock(prepared.userId());
            allowance.require(prepared.userId());
            long created = drafting.insertSession(prepared.userId(), prepared.ownerOrgId(), documentId,
                prepared.conversationId(), hints(prepared.fields()));
            drafting.insertStep(created, DraftingStates.FIRST_STEP);
            return created;
        });

        return view(drafting.session(sessionId).orElseThrow());
    }

    // ---- checks ----------------------------------------------------------------------------------------------------

    private static void requireManager(Actor actor) {
        if (actor.grants().stream().noneMatch(grant -> MANAGERS.contains(grant.roleCode()))) {
            throw new AccessDeniedException("Only roles that manage courses may draft a course with the assistant");
        }
    }

    /**
     * An instructor's unit is their own. An admin may name a unit inside their scope; one who names none drafts for
     * their own unit, which must also be inside their scope.
     */
    private long ownerOrg(Actor actor, Long requested) {
        boolean admin = actor.grants().stream().anyMatch(grant -> ADMINS.contains(grant.roleCode()));
        Long own = actor.person().orgId();
        if (!admin) {
            if (own == null) {
                throw BusinessException.validation("ownerOrgId", "NO_UNIT", "Tài khoản chưa thuộc đơn vị nào.");
            }
            if (requested != null && !requested.equals(own)) {
                throw new AccessDeniedException("An instructor drafts courses for their own unit only");
            }
            return own;
        }
        Long chosen = requested != null ? requested : own;
        if (chosen == null) {
            throw BusinessException.validation("ownerOrgId", "REQUIRED", "Hãy chọn đơn vị sở hữu khóa học.");
        }
        access.requireManage(actor, chosen, null, null);

        return chosen;
    }

    private static StartFields checked(StartFields in, long ownerOrgId) {
        String title = blankToNull(in.title());
        if (title != null && title.length() > 255) {
            throw BusinessException.validation("title", "INVALID_LENGTH", "Tên khóa học tối đa 255 ký tự.");
        }
        String learners = blankToNull(in.targetLearners());
        if (learners != null && learners.length() > 500) {
            throw BusinessException.validation("targetLearners", "INVALID_LENGTH",
                "Đối tượng học viên tối đa 500 ký tự.");
        }
        String level = blankToNull(in.level());
        if (level != null && !GenerationSchemas.LEVELS.contains(level)) {
            throw BusinessException.validation("level", "INVALID_VALUE",
                "Mức độ phải là basic, intermediate hoặc advanced.");
        }

        return new StartFields(title, learners, level, ownerOrgId);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    SessionRow owned(long sessionId) {
        // A session of someone else answers the same as a missing one, so an id reveals nothing.
        return drafting.session(sessionId).filter(row -> row.ownerUserId().equals(SecurityContextUtils.getUserId()))
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
    }

    // ---- views -----------------------------------------------------------------------------------------------------

    private SessionView view(SessionRow row) {
        SourceView source = row.sourceDocumentId() == null ? null : drafting.document(row.sourceDocumentId())
            .map(info -> new SourceView(info.id(), info.fileName(), info.status())).orElse(null);
        List<StepView> steps = drafting.steps(row.id()).stream().map(this::stepView).toList();

        return new SessionView(row.id(), row.status(), row.currentStep(), row.courseId(), row.revisionId(),
            row.conversationId(), source, row.errorMessage(), steps, row.createTime(), row.updateTime());
    }

    private StepView stepView(DraftingStepRow step) {
        List<String> notes = step.notes() == null ? List.of() : read(step.notes(), new TypeReference<>() { });
        JsonNode proposal = step.proposal() == null ? null : read(step.proposal(), new TypeReference<>() { });
        // A confirmed outline keeps its lesson notes beside the proposal; the view sends them as their own field.
        JsonNode lessonNotes = proposal instanceof ObjectNode object ? object.remove(LESSON_NOTES) : null;

        return new StepView(step.stepNo(), step.state(), proposal, notes, step.errorMessage(), step.updateTime(),
            lessonNotes);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String hints(StartFields fields) {
        ObjectNode hints = json.createObjectNode();
        if (fields.title() != null) {
            hints.put("title", fields.title());
        }
        if (fields.targetLearners() != null) {
            hints.put("targetLearners", fields.targetLearners());
        }
        if (fields.level() != null) {
            hints.put("level", fields.level());
        }

        return hints.toString();
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
