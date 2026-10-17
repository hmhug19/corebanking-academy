package vn.com.coreacademy.identity.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.identity.enums.ScopeType;

public record GrantRequest(
    @NotNull @Positive Long roleId,
    @Schema(description = "Phạm vi gắn với vai trò trong cùng grant; chọn từ assignableScopes của /roles.")
    @NotNull ScopeType scope,
    @Schema(description = "ORG bắt buộc có; TEAM nếu gửi phải khớp tổ chức nhóm; OWN/GLOBAL không gửi.")
    @Positive Long orgId,
    @Schema(description = "TEAM bắt buộc có; OWN, GLOBAL và ORG không gửi teamId.")
    @Positive Long teamId) {
}
