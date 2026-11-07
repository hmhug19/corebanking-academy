package vn.com.coreacademy.learning.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.learning.dto.LearningLevelViews.LevelStep;

/** Cấu hình XP, bậc thang cấp độ và lịch ngày làm việc do quản trị hệ thống chỉnh. */
public final class GamificationConfigDtos {
    private GamificationConfigDtos() {
    }

    /** Một bậc nhập vào; số cấp do thứ tự trong danh sách (từ 1). */
    public record LevelInput(
        @Schema(description = "Tên cấp, 1 đến 100 ký tự sau khi cắt khoảng trắng.") @NotNull @Size(max = 100)
        String name,
        @Schema(description = "Tổng XP tối thiểu; bậc đầu bằng 0 và các bậc tăng dần ngặt.") @NotNull @Min(0)
        Integer minXp) {
    }

    /** Lưu một phiên bản cấu hình mới. */
    public record SaveConfig(
        @Schema(description = "XP mỗi bài học hoàn thành lần đầu.") @NotNull @Min(0) @Max(10000) Integer lessonXp,
        @Schema(description = "XP mỗi bài kiểm tra đạt lần đầu.") @NotNull @Min(0) @Max(10000) Integer quizPassXp,
        @Schema(description = "XP thưởng khi đạt ngay lượt đầu với điểm từ ngưỡng.") @NotNull @Min(0) @Max(10000)
        Integer firstTryBonusXp,
        @Schema(description = "Ngưỡng điểm % của thưởng đạt ngay lần đầu, 0 đến 100.") @NotNull @Min(0) @Max(100)
        Integer firstTryThresholdPercent,
        @Schema(description = "XP mỗi đóng góp hữu ích trong cộng đồng.") @NotNull @Min(0) @Max(10000)
        Integer communityXp,
        @Schema(description = "XP cộng đồng tối đa mỗi người mỗi ngày.") @NotNull @Min(0) @Max(100000)
        Integer communityDailyCap,
        @Schema(description = "Bậc thang cấp độ, 1 đến 20 bậc.") @NotEmpty @Size(max = 20) List<@Valid @NotNull
        LevelInput> levels) {
    }

    /** Một phiên bản cấu hình đã lưu, kèm người lưu và lúc lưu (null với phiên bản gieo sẵn). */
    public record ConfigVersionView(int versionNo, int lessonXp, int quizPassXp, int firstTryBonusXp,
                                    int firstTryThresholdPercent, int communityXp, int communityDailyCap,
                                    List<LevelStep> levels, String createdBy, Instant createdAt, boolean current) {
    }

    /** Ngày ngoại lệ của lịch: HOLIDAY (nghỉ) hoặc MAKEUP_WORKDAY (làm bù). */
    public record CalendarDayInput(
        @NotNull LocalDate date,
        @Schema(description = "HOLIDAY hoặc MAKEUP_WORKDAY.") @NotBlank String type,
        @Schema(description = "Tên ngày nghỉ hoặc ghi chú, tối đa 200 ký tự.") @Size(max = 200) String note) {
    }

    /** Thay toàn bộ ngày ngoại lệ của một năm. */
    public record SaveCalendarYear(@Schema(description = "Mọi ngày ngoại lệ của năm; rỗng là xóa hết.")
                                   @NotNull List<@Valid @NotNull CalendarDayInput> days) {
    }

    public record CalendarDayView(LocalDate date, String type, String note) {
    }
}
