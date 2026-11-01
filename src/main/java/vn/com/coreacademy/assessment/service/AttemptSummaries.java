package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoreMethod;
import vn.com.coreacademy.assessment.dto.AssessmentViews.AttemptSummary;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.entity.QuizAttempt;

/**
 * Quy tắc tổng hợp lượt của (đề, học viên), dùng chung cho màn giới thiệu đề và điểm trên tổng quan học tập để hai nơi
 * không thể lệch nhau.
 */
final class AttemptSummaries {
    private AttemptSummaries() {
    }

    /** Lượt đã chấm (SUBMITTED, AUTO_SUBMITTED) theo thứ tự nộp; lượt đang làm và VOIDED không có điểm nên bị bỏ. */
    static List<QuizAttempt> graded(List<QuizAttempt> attempts) {
        return attempts.stream()
            .filter(a -> Set.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED).contains(a.getStatus()))
            .sorted(Comparator.comparing(QuizAttempt::getSubmittedAt).thenComparing(QuizAttempt::getId))
            .toList();
    }

    /** Lượt chấm gần nhất theo {@link #graded}, hoặc null khi chưa có lượt nào. */
    static QuizAttempt lastGraded(List<QuizAttempt> attempts) {
        List<QuizAttempt> graded = graded(attempts);
        return graded.isEmpty() ? null : graded.get(graded.size() - 1);
    }

    /**
     * Tổng hợp lượt của (đề, học viên). Lượt tính ngay khi bắt đầu, trừ VOIDED. passed/finalScore theo score_method:
     * BEST = từng đạt/điểm cao nhất, LAST = lượt chấm gần nhất.
     */
    static AttemptSummary summarize(Quiz quiz, List<QuizAttempt> attempts, int extra, Instant now) {
        List<QuizAttempt> graded = graded(attempts);
        int used = (int) attempts.stream().filter(a -> !AttemptStatus.VOIDED.equals(a.getStatus())).count();
        int left = Math.max(0, quiz.getMaxAttempts() + extra - used);
        BigDecimal best = graded.stream().map(QuizAttempt::getScorePercent).filter(Objects::nonNull)
            .max(BigDecimal::compareTo).orElse(null);
        QuizAttempt last = graded.isEmpty() ? null : graded.get(graded.size() - 1);
        boolean everPassed = graded.stream().anyMatch(a -> Boolean.TRUE.equals(a.getPassed()));
        boolean byLast = ScoreMethod.LAST.equals(quiz.getScoreMethod());
        boolean passed = byLast ? last != null && Boolean.TRUE.equals(last.getPassed()) : everPassed;
        BigDecimal lastScore = last == null ? null : last.getScorePercent();
        Long running = attempts.stream().filter(a -> AttemptStatus.IN_PROGRESS.equals(a.getStatus()))
            .filter(a -> !now.isAfter(a.getDeadlineAt().plusSeconds(a.getGraceSeconds())))
            .map(QuizAttempt::getId).findFirst().orElse(null);
        Instant lastSubmitted = last == null ? null : last.getSubmittedAt();
        Instant next = lastSubmitted == null || quiz.getRetakeWaitHours() == 0 ? null
            : lastSubmitted.plus(Duration.ofHours(quiz.getRetakeWaitHours()));
        return new AttemptSummary(quiz.getMaxAttempts(), extra, used, left, best, lastScore, byLast ? lastScore : best,
            passed, everPassed, running, lastSubmitted, next);
    }
}
