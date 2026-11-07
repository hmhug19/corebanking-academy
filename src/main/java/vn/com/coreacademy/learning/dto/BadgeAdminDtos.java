package vn.com.coreacademy.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Quản lý huy hiệu của quản trị hệ thống. */
public final class BadgeAdminDtos {
    private BadgeAdminDtos() {
    }

    /** Nội dung tạo hoặc sửa một huy hiệu; tham số luật tùy loại luật. */
    public record SaveBadge(
        @Schema(description = "Tên huy hiệu, 1 đến 100 ký tự.") @NotBlank @Size(max = 100) String name,
        @Schema(description = "Mô tả ngắn, 1 đến 300 ký tự.") @NotBlank @Size(max = 300) String description,
        @Schema(description = "Tên biểu tượng Lucide, chữ thường, số và dấu gạch ngang.") @NotBlank @Size(max = 50)
        String icon,
        @Schema(description = "COURSES_COMPLETED, PROGRAM_COMPLETED, FIRST_TRY_PERFECT, ASSIGNED_BEFORE_DEADLINE hoặc "
            + "STREAK_WORKING_DAYS.") @NotNull String ruleType,
        @Schema(description = "Ngưỡng N từ 1 đến 1000; bắt buộc với mọi loại trừ PROGRAM_COMPLETED, và phải để trống "
            + "với loại đó.") Integer paramN,
        @Schema(description = "Chương trình của luật PROGRAM_COMPLETED; phải để trống với loại khác.")
        Long paramProgramId) {
    }

    /** Một huy hiệu cho quản trị. locked là true khi đã có người nhận: luật không còn sửa được. */
    public record AdminBadgeView(
        @Schema(description = "ID huy hiệu.") long badgeId,
        @Schema(description = "Mã ổn định.") String code,
        String name,
        String description,
        @Schema(description = "Tên biểu tượng Lucide.") String icon,
        String ruleType,
        Integer paramN,
        Long paramProgramId,
        @Schema(description = "Tên chương trình của luật PROGRAM_COMPLETED; null với loại khác.")
        String programName,
        @Schema(description = "Điều kiện nhận bằng tiếng Việt.") String condition,
        @Schema(description = "active hoặc retired.") String status,
        @Schema(description = "Đã có người nhận, luật bị khóa.") boolean locked,
        @Schema(description = "Số người đã nhận.") long holders,
        @Schema(description = "Trao bù nền: RUNNING, DONE hoặc FAILED.") String backfillStatus,
        @Schema(description = "Số người đủ điều kiện tìm thấy khi bắt đầu trao bù.") int backfillTotal,
        @Schema(description = "Số người đã xử lý trong lần trao bù gần nhất.") int backfillDone) {
    }
}
