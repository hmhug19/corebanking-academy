package vn.com.coreacademy.assistant.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Một thay đổi dữ liệu do trợ lý đề xuất; chưa có gì thay đổi cho tới khi người dùng xác nhận.")
public record PendingActionView(
    String id,
    @Schema(description = "Mã công cụ, hiện chỉ có update_profile.") String tool,
    String title,
    List<Change> changes,
    @Schema(description = "Sau thời điểm này yêu cầu không còn hiệu lực.") Instant expiresAt) {

    @Schema(description = "Giá trị cũ và mới của một trường; null nghĩa là trống.")
    public record Change(String field, String label, String from, String to) {
    }
}
