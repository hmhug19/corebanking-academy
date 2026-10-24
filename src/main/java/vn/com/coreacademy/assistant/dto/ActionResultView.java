package vn.com.coreacademy.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Kết quả sau khi xác nhận một yêu cầu.")
public record ActionResultView(
    String actionId,
    String tool,
    String message,
    @Schema(description = "Dữ liệu theo từng công cụ; với update_profile là /auth/me đã làm mới.") Object data) {
}
