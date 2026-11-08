package vn.com.coreacademy.assessment.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AuditAction;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.SubmitType;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.entity.AttemptAnswer;
import vn.com.coreacademy.assessment.entity.AttemptQuestion;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.entity.QuizAttemptGrant;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.assessment.repository.QuizAttemptRepository;
import vn.com.coreacademy.assessment.repository.custom.QuizAttemptRepositoryCustom;
import vn.com.coreacademy.assessment.repository.custom.AssessmentResultRepository;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess.ManagedCourse;
import vn.com.coreacademy.learning.service.LearningWriteLock;

/**
 * Quản trị lượt thi của giảng viên/quản trị khóa: tra cứu, hủy lượt và cấp thêm lượt riêng cho học viên. Quyền xét
 * theo khóa học chứa đề; mọi thao tác ghi đều có audit cùng transaction.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class QuizAttemptAdminService {
    private static final Set<String> SORTS = Set.of("id", "startedAt", "submittedAt", "scorePercent");
    private static final Set<String> STATUSES = Set.of(AttemptStatus.IN_PROGRESS, AttemptStatus.SUBMITTED,
        AttemptStatus.AUTO_SUBMITTED, AttemptStatus.VOIDED);

    private final LearningAssessmentAccess learning;
    private final AssessmentRepository repository;
    private final QuizAttemptRepository attempts;
    private final AttemptReadModel readModel;
    private final LearningWriteLock lock;
    private final IdentityAuditService audit;
    private final AttemptGrader grader;
    private final RegradeService regrades;
    private final AssessmentResultRepository results;

    public PageResponse<AssessmentViews.AdminAttemptRow> search(Long quizId, String userId, String status,
                                                                Boolean passed, Instant from, Instant to, int page,
                                                                int size, String sort) {
        managedQuiz(quizId);
        if (status != null && !STATUSES.contains(status)) {
            throw BusinessException.validation("status", "INVALID_VALUE", "Giá trị không hợp lệ.");
        }
        if (from != null && to != null && !to.isAfter(from)) {
            throw BusinessException.validation("to", "OUT_OF_RANGE", "Thời điểm kết thúc phải sau thời điểm bắt đầu.");
        }
        var filter = new QuizAttemptRepositoryCustom.Filter(quizId, blankToNull(userId), status, passed, from, to);
        var result = attempts.search(filter, PageQuery.of(page, size, sort, SORTS));
        Map<String, String> names = learning.fullNames(result.getContent().stream()
            .map(AssessmentViews.AttemptListRow::userId).distinct().toList());
        return PageResponse.from(result.map(r -> new AssessmentViews.AdminAttemptRow(r.attemptId(), r.userId(),
            names.get(r.userId()), r.attemptNo(), r.status(), r.submitType(), r.startedAt(), r.submittedAt(),
            r.totalQuestions(), r.correctCount(), r.scorePercent(), r.passed(), r.orgId())));
    }

    /** Toàn bộ bài làm kèm đáp án đúng, IP/thiết bị và số lần đổi đáp án để xử lý khiếu nại. */
    public AssessmentViews.AdminAttemptDetail detail(Long attemptId) {
        QuizAttempt attempt = repository.require(QuizAttempt.class, attemptId);
        Quiz quiz = managedQuiz(attempt.getQuizId()).quiz();
        List<AttemptQuestion> questions = repository.attemptQuestions(attemptId);
        Map<Long, AttemptAnswer> answers = readModel.answersByQuestion(attemptId);
        boolean graded = Set.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED).contains(attempt.getStatus());
        String name = learning.fullNames(List.of(attempt.getUserId())).get(attempt.getUserId());
        return new AssessmentViews.AdminAttemptDetail(attempt.getId(), quiz.getId(), quiz.getTitle(),
            attempt.getUserId(), name, attempt.getEnrollmentId(), attempt.getAttemptNo(), attempt.getStatus(),
            attempt.getSubmitType(), attempt.getStartedAt(), attempt.getDeadlineAt(), attempt.getSubmittedAt(),
            attempt.getLastSavedAt(), AttemptReadModel.duration(attempt), attempt.getTotalQuestions(),
            attempt.getCorrectCount(), attempt.getRawScore(), attempt.getMaxScore(), attempt.getScorePercent(),
            attempt.getPassPercent(), attempt.getPassed(), attempt.getOrgId(), attempt.getTeamId(),
            attempt.getClientIp(), attempt.getUserAgent(), attempt.getVoidReason(), attempt.getVoidedBy(),
            attempt.getVoidedAt(), graded ? readModel.competencies(questions, answers) : List.of(),
            questions.stream().map(q -> readModel.adminQuestion(q, answers.get(q.getId()))).toList(),
            regrades.history(attemptId));
    }

    /**
     * Hủy lượt (gian lận, sự cố hệ thống). Lượt VOIDED không tính vào số lượt đã dùng và điểm; dữ liệu bài làm giữ
     * nguyên để đối chiếu. Hủy lượt đang làm giải phóng chỗ cho lượt mới.
     */
    @Transactional
    public AssessmentViews.AdminAttemptDetail voidAttempt(Long attemptId, AssessmentRequests.VoidRequest request) {
        ManagedQuiz managed = managedQuiz(repository.require(QuizAttempt.class, attemptId).getQuizId());
        String actor = learning.actor().person().id();
        // Khóa chung trước, khóa dòng lượt sau: cùng thứ tự với các luồng ghi khác để tránh deadlock.
        lock.acquire();
        QuizAttempt attempt = repository.lockAttempt(attemptId);

        if (AttemptStatus.VOIDED.equals(attempt.getStatus())) {
            throw new BusinessException(AssessmentResponseCode.CONFLICT);
        }
        String previous = attempt.getStatus();
        attempt.setStatus(AttemptStatus.VOIDED);
        attempt.setVoidReason(request.reason().trim());
        attempt.setVoidedBy(actor);
        attempt.setVoidedAt(ServerTime.now());
        repository.flush();
        results.refresh(attempt);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("quizId", attempt.getQuizId());
        changes.put("userId", attempt.getUserId());
        changes.put("previousStatus", previous);
        audit.record(actor, AuditAction.ATTEMPT_VOIDED, "quiz_attempt", attemptId.toString(),
            managed.course().ownerOrgId(), changes);
        return detail(attemptId);
    }

    /**
     * Quản trị nộp hộ lượt đang làm khi có sự cố (mất mạng kéo dài, treo trình duyệt). Chấm với đáp án đã lưu,
     * ghi SUBMITTED/ADMIN; giờ nộp không vượt deadline của lượt.
     */
    @Transactional
    public AssessmentViews.AdminAttemptDetail forceSubmit(Long attemptId) {
        ManagedQuiz managed = managedQuiz(repository.require(QuizAttempt.class, attemptId).getQuizId());
        String actor = learning.actor().person().id();
        lock.acquire();
        QuizAttempt attempt = repository.lockAttempt(attemptId);

        if (!AttemptStatus.IN_PROGRESS.equals(attempt.getStatus())) {
            throw new BusinessException(AssessmentResponseCode.CONFLICT);
        }
        grader.submitOnBehalf(attempt, AttemptStatus.SUBMITTED, SubmitType.ADMIN, ServerTime.now());
        repository.flush();

        audit.record(actor, AuditAction.ATTEMPT_FORCE_SUBMITTED, "quiz_attempt", attemptId.toString(),
            managed.course().ownerOrgId(), Map.of("quizId", attempt.getQuizId(), "userId", attempt.getUserId(),
                "scorePercent", attempt.getScorePercent()));
        return detail(attemptId);
    }

    public List<AssessmentViews.GrantView> grants(Long quizId, String userId) {
        managedQuiz(quizId);
        List<QuizAttemptGrant> grants = repository.grants(quizId, blankToNull(userId));
        Map<String, String> names = learning.fullNames(grants.stream().map(QuizAttemptGrant::getUserId)
            .distinct().toList());
        return grants.stream().map(g -> view(g, names.get(g.getUserId()))).toList();
    }

    public PageResponse<AssessmentViews.ExhaustedLearner> exhaustedLearners(Long quizId, String userId, int page,
                                                                             int size) {
        Quiz quiz = managedQuiz(quizId).quiz();
        var rows = repository.exhaustedLearners(quiz, userId, PageQuery.of(page, size, "id", Set.of("id")));
        Map<String, String> names = learning.fullNames(rows.getContent().stream()
            .map(AssessmentViews.ExhaustedLearnerCount::userId).toList());
        return PageResponse.from(rows.map(row -> new AssessmentViews.ExhaustedLearner(row.userId(),
            names.get(row.userId()), row.attemptsUsed())));
    }

    /** Cấp thêm lượt riêng cho một học viên có lần học của khóa; không đổi maxAttempts chung của đề. */
    @Transactional
    public AssessmentViews.GrantView grant(Long quizId, AssessmentRequests.GrantRequest request,
                                            boolean requireExhausted) {
        lock.acquire();
        ManagedQuiz managed = managedQuiz(quizId);
        String actor = learning.actor().person().id();

        String userId = request.userId().trim();
        if (!learning.hasEnrollment(managed.quiz().getCourseId(), userId)) {
            throw BusinessException.field(AssessmentResponseCode.INVALID_REFERENCE, "userId", "NOT_FOUND",
                "Học viên chưa có lần học của khóa này.");
        }
        if (requireExhausted) {
            var summary = AttemptSummaries.summarize(managed.quiz(), repository.attempts(quizId, userId),
                repository.grantedAttempts(quizId, userId), ServerTime.now());
            if (summary.attemptsLeft() > 0) {
                throw BusinessException.field(AssessmentResponseCode.CONFLICT, "userId", "ATTEMPTS_REMAIN",
                    "Thí sinh vẫn còn lượt thi. Hãy tải lại danh sách.");
            }
        }
        QuizAttemptGrant grant = new QuizAttemptGrant();
        grant.setQuizId(quizId);
        grant.setUserId(userId);
        grant.setExtraAttempts(request.extraAttempts());
        grant.setReason(request.reason().trim());
        grant.setGrantedBy(actor);
        grant.setGrantedAt(ServerTime.now());
        repository.persist(grant);
        repository.flush();

        audit.record(actor, AuditAction.ATTEMPTS_GRANTED, "quiz_attempt_grant", grant.getId().toString(),
            managed.course().ownerOrgId(), Map.of("quizId", quizId, "userId", userId,
                "extraAttempts", request.extraAttempts()));
        return view(grant, learning.fullNames(List.of(userId)).get(userId));
    }

    /** Thu hồi lượt cấp nhầm; giữ dòng lịch sử. Lượt đã dùng không bị hủy, số lượt còn lại không âm. */
    @Transactional
    public AssessmentViews.GrantView revoke(Long grantId) {
        QuizAttemptGrant grant = repository.require(QuizAttemptGrant.class, grantId);
        ManagedQuiz managed = managedQuiz(grant.getQuizId());
        String actor = learning.actor().person().id();
        lock.acquire();

        if (Boolean.TRUE.equals(grant.getDeleted())) {
            throw new BusinessException(AssessmentResponseCode.CONFLICT);
        }
        grant.setDeleted(true);
        grant.setDeletedBy(actor);
        grant.setDeletedAt(ServerTime.now());
        repository.flush();

        audit.record(actor, AuditAction.GRANT_REVOKED, "quiz_attempt_grant", grantId.toString(),
            managed.course().ownerOrgId(), Map.of("quizId", grant.getQuizId(), "userId", grant.getUserId()));
        return view(grant, learning.fullNames(List.of(grant.getUserId())).get(grant.getUserId()));
    }

    private record ManagedQuiz(Quiz quiz, ManagedCourse course) {
    }

    private ManagedQuiz managedQuiz(Long quizId) {
        Quiz quiz = repository.require(Quiz.class, quizId);
        if (Boolean.TRUE.equals(quiz.getDeleted())) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        return new ManagedQuiz(quiz, learning.requireManagedCourse(quiz.getCourseId()));
    }

    private static AssessmentViews.GrantView view(QuizAttemptGrant grant, String userName) {
        return new AssessmentViews.GrantView(grant.getId(), grant.getQuizId(), grant.getUserId(), userName,
            grant.getExtraAttempts(), grant.getReason(), grant.getGrantedBy(), grant.getGrantedAt(),
            grant.getDeleted(), grant.getDeletedBy(), grant.getDeletedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
