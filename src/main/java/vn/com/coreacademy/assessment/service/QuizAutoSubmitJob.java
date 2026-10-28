package vn.com.coreacademy.assessment.service;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;

/**
 * Tự nộp các lượt đã quá deadline + ân hạn và chấm với đáp án đã lưu. Mỗi lượt xử lý trong transaction riêng và
 * khóa dòng lượt thi, nên chạy lại hoặc chạy song song nhiều instance không chấm trùng (idempotent). Job không có
 * người dùng hiện tại và không gọi API cần security context.
 */
@Component
@Slf4j
public class QuizAutoSubmitJob {
    private final AssessmentRepository repository;
    private final AttemptGrader grader;
    private final TransactionTemplate transaction;

    public QuizAutoSubmitJob(AssessmentRepository repository, AttemptGrader grader,
                             PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.grader = grader;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "#{@assessmentProperties.autoSubmitInterval.toMillis()}",
        initialDelayString = "#{@assessmentProperties.autoSubmitInitialDelay.toMillis()}")
    public void run() {
        List<Long> ids = transaction.execute(status ->
            repository.expiredAttemptIds(ServerTime.now(), AssessmentLimits.AUTO_SUBMIT_BATCH));
        if (ids == null || ids.isEmpty()) {
            return;
        }
        int done = 0;
        for (Long id : ids) {
            try {
                Boolean graded = transaction.execute(status -> finalizeIfExpired(id));
                if (Boolean.TRUE.equals(graded)) {
                    done++;
                }
            } catch (RuntimeException exception) {
                log.error("Quiz auto-submit failed attemptId={} type={}", id, exception.getClass().getSimpleName());
            }
        }
        log.info("Quiz auto-submit finished candidates={} graded={}", ids.size(), done);
    }

    private boolean finalizeIfExpired(Long attemptId) {
        QuizAttempt attempt = repository.lockAttempt(attemptId);
        // Học viên có thể vừa nộp trong lúc job chờ khóa; kiểm tra lại sau khi giữ khóa.
        if (!AttemptStatus.IN_PROGRESS.equals(attempt.getStatus()) || !grader.expired(attempt, ServerTime.now())) {
            return false;
        }
        grader.finalizeExpired(attempt);
        repository.flush();
        return true;
    }
}
