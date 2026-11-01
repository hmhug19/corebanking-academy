package vn.com.coreacademy.assessment.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Chỉ trả kết quả của caller, không chứa snapshot đáp án hoặc dữ liệu chấm nội bộ. */
public final class AssessmentDashboard {
    private AssessmentDashboard() {
    }

    public record Counts(Long assessedQuizCount, Long passedQuizCount) {
    }

    public record Score(Long quizId, String quizTitle, Long courseId, Long attemptId, Long enrollmentId,
                        BigDecimal scorePercent, Boolean passed, Instant submittedAt) {
    }

    public record Summary(Counts counts, Score latest, Score best) {
    }

    public record Competency(Long competencyId, String code, String name, Long assessedQuizCount,
                             Long questionCount, Long correctCount, BigDecimal earnedScore,
                             BigDecimal maxScore, BigDecimal percent) {
    }
}
