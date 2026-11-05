package vn.com.coreacademy.identity.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** A request as the admin inbox shows it. It has no password field of any kind. */
public record AccessRequestView(
    Long requestId,
    @Schema(description = "REGISTER hoặc PASSWORD_RESET.") String kind,
    @Schema(description = "PENDING, APPROVED hoặc REJECTED.") String status,
    @Schema(description = "Mã cán bộ được yêu cầu hoặc được hỏi đến; giữ nguyên số 0 đầu.") String staffCode,
    String fullName,
    String email,
    Long orgId,
    String orgName,
    String targetUserId,
    @Schema(description = "False khi yêu cầu đặt lại mật khẩu không khớp tài khoản nào.") boolean accountFound,
    Instant createdTime,
    String resolvedBy,
    Instant resolvedTime,
    String resolutionNote) {
}
