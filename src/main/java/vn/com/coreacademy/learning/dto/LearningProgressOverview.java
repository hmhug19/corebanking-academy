package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public record LearningProgressOverview(Instant serverTime, String timeZone, Counts counts,
                                       Availability activity, AssessmentSection assessment,
                                       Availability competency) {
    /**
     * assigned và mandatory là tổng của danh sách khóa của tôi với assigned=true hoặc requirement=mandatory, nên tab
     * trên màn hình học tập luôn khớp với danh sách.
     */
    public record Counts(Long total, Long completed, Long inProgress, Long notStarted, Long overdue, Long assigned,
                         Long mandatory) {
    }

    /** Phần điểm: AssessmentOverview khi các khóa hiện tại có đề đã xuất bản, ngược lại Availability unavailable. */
    @Schema(oneOf = {Availability.class, AssessmentOverview.class})
    public sealed interface AssessmentSection permits Availability, AssessmentOverview {
    }

    public record Availability(String availability) implements AssessmentSection {
    }

    /**
     * Điểm trên các đề đã xuất bản của khóa hiện tại của caller (cùng tập khóa với counts). Mọi con số lấy từ cùng quy
     * tắc tổng hợp với AttemptSummary của màn giới thiệu đề.
     */
    public record AssessmentOverview(
        @Schema(description = "Luôn là available.") String availability,
        @Schema(description = "Lượt chấm gần nhất qua mọi đề; null khi chưa có lượt nào.") LastScore last,
        @Schema(description = "Điểm cao nhất (bestScore) qua các đề; null khi chưa có lượt chấm.")
        BigDecimal bestScorePercent,
        @Schema(description = "Trung bình điểm cuối (finalScore) của từng đề đã có lượt chấm, 2 chữ số, làm tròn lên "
            + "từ .5; null khi chưa có lượt chấm.") BigDecimal averageScorePercent,
        @Schema(description = "Số đề có passed = true theo scoreMethod của đề.") int passedQuizCount,
        @Schema(description = "Số đề đã xuất bản, chưa xóa của các khóa hiện tại, gồm cả đề luyện tập.") int quizCount)
        implements AssessmentSection {
        public static final String AVAILABLE = "available";

        /** passed là kết quả của chính lượt này, không theo scoreMethod của đề. */
        public record LastScore(Long quizId, String quizTitle, Long courseId, Long attemptId, BigDecimal scorePercent,
                                Boolean passed, Instant submittedAt) {
        }
    }
}
