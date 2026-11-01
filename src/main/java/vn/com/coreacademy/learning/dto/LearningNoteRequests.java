package vn.com.coreacademy.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.learning.constants.LearningCodes.AnchorType;
import vn.com.coreacademy.learning.constants.LearningLimits;

/** Payload ghi chú và đánh dấu; người dùng luôn là caller, không nhận từ body. */
public final class LearningNoteRequests {
    private LearningNoteRequests() {
    }

    @Schema(description = "Vị trí neo. Trang và giây được kiểm theo tài nguyên thật của khối ở service.")
    public record AnchorRequest(
        @NotNull @Pattern(regexp = AnchorType.PATTERN) String type,
        @Positive Long blockId,
        @Schema(description = "Số trang PDF từ 1; chỉ dùng với type=pdf.") Integer page,
        @Schema(description = "Giây video nguyên từ 0; chỉ dùng với type=video.") Integer second) {
    }

    public record AddNote(
        @NotNull @Valid AnchorRequest anchor,
        @Schema(description = "Nội dung sau khi cắt khoảng trắng đầu cuối, 1 đến 4000 ký tự.") @NotNull String text) {
    }

    public record ToggleBookmark(
        @NotNull @Valid AnchorRequest anchor,
        @Schema(description = "Nhãn tùy chọn; bỏ trống thì backend đặt nhãn mặc định từ tên khối và vị trí.")
        @Size(max = LearningLimits.MAX_BOOKMARK_LABEL_LENGTH) String label) {
    }
}
