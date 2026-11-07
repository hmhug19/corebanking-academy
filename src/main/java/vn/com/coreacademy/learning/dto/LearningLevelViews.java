package vn.com.coreacademy.learning.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public final class LearningLevelViews {
    private LearningLevelViews() {
    }

    /** Một bậc của bậc thang cấp độ. */
    public record LevelStep(int level, String name, int minXp) {
    }

    /**
     * Cấp của một học viên. level là cấp cao nhất từng đạt nên không bao giờ giảm, kể cả khi quản trị nâng ngưỡng.
     * Ở cấp cao nhất xpToNext và progressPercent là null.
     */
    public record MyLevel(
        @Schema(description = "Số cấp, từ 1.") int level,
        @Schema(description = "Tên cấp.") String name,
        @Schema(description = "Tổng XP hiện tại.") long xp,
        @Schema(description = "XP còn thiếu để lên cấp kế tiếp; null ở cấp cao nhất.") Long xpToNext,
        @Schema(description = "Phần trăm quãng từ cấp này tới cấp kế tiếp, 0-100; null ở cấp cao nhất.")
        Integer progressPercent) {
    }
}
