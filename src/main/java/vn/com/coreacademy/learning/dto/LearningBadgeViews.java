package vn.com.coreacademy.learning.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public final class LearningBadgeViews {
    private LearningBadgeViews() {
    }

    /** Tiến độ của huy hiệu chương trình: số khóa đã hoàn thành trên tổng số khóa của chương trình. */
    public record BadgeProgress(
        @Schema(description = "Số khóa của chương trình đã hoàn thành.") int done,
        @Schema(description = "Tổng số khóa của chương trình.") int total) {
    }

    /**
     * Một huy hiệu trên hồ sơ. earnedAt là null với huy hiệu chưa nhận; retired là true với huy hiệu đã ngừng dùng mà
     * người này đã nhận trước đó. progress chỉ có ở huy hiệu chương trình chưa nhận.
     */
    public record BadgeView(
        @Schema(description = "ID huy hiệu.") long badgeId,
        @Schema(description = "Tên huy hiệu.") String name,
        @Schema(description = "Mô tả ngắn.") String description,
        @Schema(description = "Tên biểu tượng Lucide.") String icon,
        @Schema(description = "Điều kiện nhận, bằng tiếng Việt.") String condition,
        @Schema(description = "Lúc nhận; null nếu chưa nhận.") Instant earnedAt,
        @Schema(description = "Huy hiệu đã ngừng dùng (người nhận vẫn giữ).") boolean retired,
        @Schema(description = "Số khóa x/y của huy hiệu chương trình chưa nhận; null với loại khác.")
        BadgeProgress progress) {
    }

    /** Huy hiệu của caller: đã nhận (mới nhất trước) rồi chưa nhận (theo thứ tự hiển thị). */
    public record MyBadges(List<BadgeView> earned, List<BadgeView> notEarned) {
    }
}
