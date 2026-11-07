package vn.com.coreacademy.learning.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Báo cáo tác động của gamification: chỉ số tổng hợp, không có dòng theo người. */
public final class GamificationImpactDtos {
    private GamificationImpactDtos() {
    }

    @Schema(description = "Số học viên hoạt động trong một tuần (thứ Hai là ngày đầu tuần, giờ Việt Nam).")
    public record WeekPoint(LocalDate weekStart, int learners) {
    }

    @Schema(description = "Nguồn XP: số XP và số học viên có dòng sổ từ nguồn đó trong kỳ.")
    public record SourceXp(String source, long xp, int learners) {
    }

    @Schema(description = "Số huy hiệu được trao trong kỳ.")
    public record BadgeCount(long badgeId, String name, int awarded) {
    }

    @Schema(description = "Số học viên đang ở một cấp.")
    public record LevelCount(int level, String name, int learners) {
    }

    @Schema(description = "Học viên tắt hiển thị tên trên bảng xếp hạng.")
    public record OptOut(int hidden, int learners, Double ratePercent) {
    }

    @Schema(description = "Khóa học được giao có hạn bắt đầu trong kỳ: số giao, số đã hoàn thành, số hoàn thành đúng hạn.")
    public record Assigned(int assigned, int completed, int completedOnTime, Double completionRatePercent,
                           Double onTimeRatePercent) {
    }

    @Schema(description = "Một cửa sổ so sánh trước hoặc sau ngày ra mắt, ngày kết thúc bao gồm.")
    public record Window(LocalDate from, LocalDate to, Double weeklyActiveLearners, Assigned assigned) {
    }

    @Schema(description = "So sánh hai cửa sổ liền kề quanh ngày ra mắt. after là null khi chưa có ngày nào sau ra mắt.")
    public record Comparison(Instant launchedAt, int windowDays, Window before, Window after) {
    }

    public record ImpactReport(
        @Schema(description = "Đầu kỳ, bao gồm.") LocalDate from,
        @Schema(description = "Cuối kỳ, bao gồm.") LocalDate to,
        @Schema(description = "Thời điểm lưu phiên bản cấu hình 1, mốc ra mắt; null nếu chưa có.") Instant launchedAt,
        @Schema(description = "Số học viên trong phạm vi đã chọn.") int learners,
        List<WeekPoint> weeklyActive,
        Assigned assigned,
        Comparison comparison,
        List<SourceXp> xpBySource,
        List<BadgeCount> badges,
        List<LevelCount> levels,
        OptOut optOut) {
    }
}
