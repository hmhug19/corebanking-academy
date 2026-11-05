package vn.com.coreacademy.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** A visitor's registration request. The password is write-only and never printed. */
public record RegisterRequest(
    @Schema(description = "Mã cán bộ dạng chuỗi, giữ nguyên số 0 đầu; cũng là tên đăng nhập (chữ thường).")
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,20}") String staffCode,
    @NotBlank @Size(max = 255) String fullName,
    @NotBlank @Email @Size(max = 100) String email,
    @Schema(description = "Đơn vị lấy từ GET /api/v1/auth/registration-units.")
    @NotNull @Positive Long orgId,
    @NotBlank @Size(min = 6, max = 72)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Schema(description = "Mật khẩu ít nhất 6 ký tự, tối đa 72 byte UTF-8; chỉ lưu dạng băm.",
        accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
    @Override
    public String toString() {
        return "RegisterRequest[REDACTED]";
    }
}
