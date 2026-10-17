package vn.com.coreacademy.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record PasswordChangeRequest(
    @NotBlank @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Schema(description = "Mật khẩu hiện tại để xác nhận thao tác.",
        accessMode = Schema.AccessMode.WRITE_ONLY) String currentPassword,
    @NotBlank @Size(min = 6, max = 72) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Schema(description = "Khác mật khẩu hiện tại; ít nhất 6 ký tự và tối đa 72 byte UTF-8.",
        accessMode = Schema.AccessMode.WRITE_ONLY) String newPassword) {
    @Override
    public String toString() {
        return "PasswordChangeRequest[REDACTED]";
    }
}
