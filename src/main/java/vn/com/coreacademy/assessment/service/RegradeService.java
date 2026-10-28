package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AuditAction;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.RegradeOutcome;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.dto.AssessmentViews.OptionText;
import vn.com.coreacademy.assessment.entity.AttemptAnswer;
import vn.com.coreacademy.assessment.entity.AttemptQuestion;
import vn.com.coreacademy.assessment.entity.AttemptRegrade;
import vn.com.coreacademy.assessment.entity.Question;
import vn.com.coreacademy.assessment.entity.QuestionOption;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess.ManagedCourse;
import vn.com.coreacademy.learning.service.LearningWriteLock;

/**
 * Chấm lại các lượt đã chấm của một đề sau khi giảng viên sửa đáp án đúng của một câu. Đáp án mới lấy từ phương án
 * hiện tại của câu và khớp theo ID phương án trong snapshot; chỉ áp dụng khi điểm của lượt TĂNG (thay đổi có lợi cho
 * học viên). Lượt bị giảm điểm chỉ được báo trong kết quả. Lượt đang làm/VOIDED không bị đụng tới.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RegradeService {
    private final LearningAssessmentAccess learning;
    private final AssessmentRepository repository;
    private final AttemptJson json;
    private final LearningWriteLock lock;
    private final IdentityAuditService audit;

    /**
     * dryRun (mặc định) chỉ tính và trả kết quả từng lượt. Áp dụng thật giữ write lock, khóa từng lượt, cập nhật
     * snapshot đáp án, điểm câu và tổng điểm, ghi attempt_regrades và một audit cho cả đợt trong cùng transaction.
     */
    @Transactional
    public AssessmentViews.RegradeResult regrade(Long quizId, AssessmentRequests.RegradeRequest request) {
        boolean dryRun = request.dryRun() == null || request.dryRun();
        Quiz quiz = repository.require(Quiz.class, quizId);
        if (Boolean.TRUE.equals(quiz.getDeleted())) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        ManagedCourse course = learning.requireManagedCourse(quiz.getCourseId());
        String actor = learning.actor().person().id();
        if (!dryRun) {
            lock.acquire();
        }

        Question question = repository.require(Question.class, request.questionId());
        if (Boolean.TRUE.equals(question.getDeleted()) || !question.getCourseId().equals(quiz.getCourseId())) {
            throw BusinessException.field(AssessmentResponseCode.INVALID_REFERENCE, "questionId", "NOT_FOUND",
                "Câu hỏi không tồn tại hoặc không thuộc khóa của bài kiểm tra.");
        }
        List<Long> newKey = repository.options(List.of(question.getId())).stream().filter(QuestionOption::getCorrect)
            .map(QuestionOption::getId).toList();
        Set<Long> newKeySet = Set.copyOf(newKey);
        List<AttemptQuestion> snapshots = repository.gradedSnapshots(quizId, question.getId());
        String batch = dryRun ? null : UUID.randomUUID().toString();

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<AssessmentViews.RegradeItem> items = new ArrayList<>();
        int applied = 0;
        for (AttemptQuestion snapshot : snapshots) {
            QuizAttempt attempt = dryRun ? repository.require(QuizAttempt.class, snapshot.getAttemptId())
                : repository.lockAttempt(snapshot.getAttemptId());
            if (!Set.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED).contains(attempt.getStatus())) {
                continue;
            }
            Outcome outcome = evaluate(attempt, snapshot, question, newKeySet);
            counts.merge(group(outcome.code()), 1, Integer::sum);
            boolean apply = !dryRun && RegradeOutcome.INCREASED.equals(outcome.code());
            if (apply) {
                applyChange(batch, quizId, question.getId(), request.reason().trim(), actor, attempt, snapshot,
                    outcome, newKey);
                applied++;
            }
            items.add(new AssessmentViews.RegradeItem(attempt.getId(), attempt.getUserId(), null,
                attempt.getAttemptNo(), outcome.oldPercent(), outcome.newPercent(), outcome.oldPassed(),
                outcome.newPassed(), outcome.code(), apply));
        }
        Map<String, String> names = learning.fullNames(items.stream().map(AssessmentViews.RegradeItem::userId)
            .distinct().toList());
        items = items.stream().map(i -> new AssessmentViews.RegradeItem(i.attemptId(), i.userId(),
            names.get(i.userId()), i.attemptNo(), i.oldScorePercent(), i.newScorePercent(), i.oldPassed(),
            i.newPassed(), i.outcome(), i.applied())).toList();

        if (applied > 0) {
            repository.flush();
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("batchId", batch);
            changes.put("questionId", question.getId());
            changes.put("applied", applied);
            changes.put("reason", request.reason().trim());
            audit.record(actor, AuditAction.ATTEMPTS_REGRADED, "quiz", quizId.toString(), course.ownerOrgId(),
                changes);
        }
        return new AssessmentViews.RegradeResult(dryRun, applied > 0 ? batch : null, question.getId(), newKey,
            items.size(), counts.getOrDefault(RegradeOutcome.INCREASED, 0),
            counts.getOrDefault(RegradeOutcome.DECREASED, 0), counts.getOrDefault(RegradeOutcome.UNCHANGED, 0),
            counts.getOrDefault("SKIPPED", 0), applied, items);
    }

    /** Lịch sử chấm lại của một lượt (cho màn chi tiết quản trị), cũ nhất trước. */
    public List<AssessmentViews.RegradeHistory> history(Long attemptId) {
        return repository.regrades(attemptId).stream().map(r -> new AssessmentViews.RegradeHistory(r.getId(),
            r.getBatchId(), r.getQuestionId(), r.getReason(), json.ids(r.getOldCorrectOptionIds()),
            json.ids(r.getNewCorrectOptionIds()), r.getOldScorePercent(), r.getNewScorePercent(), r.getOldPassed(),
            r.getNewPassed(), r.getRegradedBy(), r.getRegradedAt())).toList();
    }

    private record Outcome(String code, BigDecimal oldPercent, BigDecimal newPercent, Boolean oldPassed,
                           Boolean newPassed, AttemptAnswer answer, boolean newExact, BigDecimal newScore,
                           BigDecimal newRaw, int newCorrectCount) {
    }

    /** Tính lại riêng câu bị sửa trên lượt; tổng điểm = điểm cũ − điểm cũ của câu + điểm mới của câu. */
    private Outcome evaluate(QuizAttempt attempt, AttemptQuestion snapshot, Question question, Set<Long> newKey) {
        BigDecimal oldPercent = attempt.getScorePercent();
        Boolean oldPassed = attempt.getPassed();
        if (!snapshot.getQuestionType().equals(question.getQuestionType())) {
            return new Outcome(RegradeOutcome.SKIPPED_TYPE_CHANGED, oldPercent, oldPercent, oldPassed, oldPassed,
                null, false, null, null, 0);
        }
        Set<Long> offered = new HashSet<>(json.options(snapshot.getOptionsSnapshot()).stream().map(OptionText::id)
            .toList());
        if (newKey.isEmpty() || !offered.containsAll(newKey)) {
            return new Outcome(RegradeOutcome.SKIPPED_OPTIONS_CHANGED, oldPercent, oldPercent, oldPassed, oldPassed,
                null, false, null, null, 0);
        }
        if (Set.copyOf(json.ids(snapshot.getCorrectOptionIds())).equals(newKey)) {
            return new Outcome(RegradeOutcome.UNCHANGED, oldPercent, oldPercent, oldPassed, oldPassed, null, false,
                null, null, 0);
        }

        AttemptAnswer answer = repository.answer(snapshot.getId()).orElseThrow();
        Set<Long> selected = Set.copyOf(json.ids(answer.getSelectedOptionIds()));
        boolean newExact = selected.equals(newKey);
        BigDecimal oldScore = answer.getScoreAwarded() == null ? BigDecimal.ZERO : answer.getScoreAwarded();
        BigDecimal newScore = AttemptGrader.score(attempt.getScoringMode(), snapshot, selected, newKey, newExact);
        BigDecimal newRaw = attempt.getRawScore().subtract(oldScore).add(newScore);
        int newCorrect = attempt.getCorrectCount() - (Boolean.TRUE.equals(answer.getCorrect()) ? 1 : 0)
            + (newExact ? 1 : 0);
        BigDecimal newPercent = AttemptGrader.percent(newRaw, attempt.getMaxScore());
        boolean newPassed = AttemptGrader.passes(newRaw, attempt.getMaxScore(), attempt.getPassPercent());
        int direction = newRaw.compareTo(attempt.getRawScore());
        String code = direction > 0 ? RegradeOutcome.INCREASED
            : direction < 0 ? RegradeOutcome.DECREASED : RegradeOutcome.UNCHANGED;
        return new Outcome(code, oldPercent, newPercent, oldPassed, newPassed, answer, newExact, newScore, newRaw,
            newCorrect);
    }

    private void applyChange(String batch, Long quizId, Long questionId, String reason, String actor,
                             QuizAttempt attempt, AttemptQuestion snapshot, Outcome outcome, List<Long> newKey) {
        AttemptRegrade history = new AttemptRegrade();
        history.setBatchId(batch);
        history.setQuizId(quizId);
        history.setAttemptId(attempt.getId());
        history.setQuestionId(questionId);
        history.setReason(reason);
        history.setOldCorrectOptionIds(snapshot.getCorrectOptionIds());
        history.setNewCorrectOptionIds(json.write(newKey));
        history.setOldCorrectCount(attempt.getCorrectCount());
        history.setOldRawScore(attempt.getRawScore());
        history.setOldScorePercent(attempt.getScorePercent());
        history.setOldPassed(attempt.getPassed());

        snapshot.setCorrectOptionIds(json.write(newKey));
        outcome.answer().setCorrect(outcome.newExact());
        outcome.answer().setScoreAwarded(outcome.newScore());
        AttemptGrader.applyTotals(attempt, outcome.newRaw(), outcome.newCorrectCount());

        history.setNewCorrectCount(attempt.getCorrectCount());
        history.setNewRawScore(attempt.getRawScore());
        history.setNewScorePercent(attempt.getScorePercent());
        history.setNewPassed(attempt.getPassed());
        history.setRegradedBy(actor);
        history.setRegradedAt(ServerTime.now());
        repository.persist(history);
    }

    private static String group(String code) {
        return code.startsWith("SKIPPED") ? "SKIPPED" : code;
    }
}
