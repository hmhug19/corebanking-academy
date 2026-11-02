package vn.com.coreacademy.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.learning.constants.LearningCodes.FeedbackKind;
import vn.com.coreacademy.learning.dto.LearningNoteRequests.AnchorRequest;

/** Payload phản hồi bài học; người dùng luôn là caller, không nhận từ body. */
public final class LearningFeedbackRequests {
    private LearningFeedbackRequests() {
    }

    public record RateLesson(
        @Schema(description = "Số sao nguyên từ 1 đến 5; ngoài khoảng đó là CORE-VALIDATION_FAILED.")
        @NotNull Integer stars) {
    }

    @Schema(description = "Vị trí neo như ghi chú; mọi loại phản hồi gửi kèm vị trí hiện tại, none khi không có.")
    public record SendFeedback(
        @Schema(description = "comment | question | report.")
        @NotNull @Pattern(regexp = FeedbackKind.SENDABLE_PATTERN) String kind,
        @Schema(description = "Nội dung sau khi cắt khoảng trắng đầu cuối, 5 đến 1000 ký tự.") @NotNull String text,
        @NotNull @Valid AnchorRequest anchor) {
    }
}
