package vn.com.coreacademy.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Điều chỉnh XP thủ công của quản trị hệ thống. */
public final class XpAdjustmentDtos {
    private XpAdjustmentDtos() {
    }

    public record AdjustXp(
        @Schema(description = "Mã cán bộ của học viên được điều chỉnh.") @NotBlank @Size(max = 20) String userId,
        @Schema(description = "Số XP có dấu, khác 0, tối đa 100000 theo trị tuyệt đối; không được làm tổng XP âm.")
        @NotNull Integer amount,
        @Schema(description = "Lý do bắt buộc sau khi cắt khoảng trắng đầu cuối, 1 đến 500 ký tự.")
        @NotNull @Size(max = 500) String reason) {
    }

    /** Dòng bù trừ đã ghi và tổng XP của học viên sau điều chỉnh. */
    public record XpAdjustmentResult(long entryId, String userId, int amount, long total) {
    }
}
