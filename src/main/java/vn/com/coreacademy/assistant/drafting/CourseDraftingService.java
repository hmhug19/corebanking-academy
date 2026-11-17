package vn.com.coreacademy.assistant.drafting;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.assistant.course.GenerationAllowance;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;
import vn.com.coreacademy.assistant.drafting.DraftingViews.BasicsConfirm;
import vn.com.coreacademy.assistant.drafting.DraftingViews.SessionView;
import vn.com.coreacademy.assistant.drafting.DraftingViews.SourceView;
import vn.com.coreacademy.assistant.drafting.DraftingViews.StartFields;
import vn.com.coreacademy.assistant.drafting.DraftingViews.StepView;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentService;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseDetail;
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
    private static final Set<String> ADMINS = Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN);

    private final CourseDraftingRepository drafting;
    private final KnowledgeDocumentService documents;
    private final LearningAccessPolicy access;
    private final GenerationAllowance allowance;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final LearningContentService courses;
    private final Validator validator;

    private record Prepared(String userId, long ownerOrgId, StartFields fields, Long conversationId) {
    }

    /**
     * Checks who may start and for which unit, the fields and the day's allowance; stores the file as a course source
     * (only its uploader reads it, it is never listed or searched) and queues step 1. The document is deleted again
     * when the session cannot be queued.
     */
    public SessionView start(MultipartFile file, StartFields fields, Long conversationId) {
        Prepared prepared = prepare(fields == null ? StartFields.NONE : fields, conversationId);
        long documentId = documents.upload(file, DocumentScope.COURSE_SOURCE, null).id();
        try {
            return queue(prepared, documentId);
        } catch (RuntimeException exception) {
            documents.delete(documentId);
            throw exception;
        }
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
        if (step != DraftingStates.FIRST_STEP) {
            // Steps 2 to 4 are confirmed by the tickets that propose them.
            throw notReady();
        }
        BasicsConfirm basics = basics(body);
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

    /** The step 1 body, checked with the course create rules; errors name the field under {@code course}. */
    private BasicsConfirm basics(JsonNode body) {
        BasicsConfirm basics;
        try {
            basics = json.treeToValue(body == null ? json.createObjectNode() : body, BasicsConfirm.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw BusinessException.validation("course", "INVALID_FORMAT", "Kiểu dữ liệu hoặc giá trị không hợp lệ.");
        }
        Set<ConstraintViolation<BasicsConfirm>> violations = validator.validate(basics);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }

        return basics;
    }

    private Prepared prepare(StartFields requested, Long conversationId) {
        Actor actor = access.actor();
        requireManager(actor);
        long ownerOrgId = ownerOrg(actor, requested.ownerOrgId());
        StartFields fields = checked(requested, ownerOrgId);
        String userId = SecurityContextUtils.getUserId();
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

        return new StepView(step.stepNo(), step.state(), proposal, notes, step.errorMessage(), step.updateTime());
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
