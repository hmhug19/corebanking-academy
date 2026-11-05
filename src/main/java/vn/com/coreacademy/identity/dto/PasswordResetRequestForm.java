package vn.com.coreacademy.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

/** A visitor's password reset request: the staff code only. */
public record PasswordResetRequestForm(
    @Schema(description = "Mã cán bộ hoặc tên đăng nhập; giữ nguyên số 0 đầu.")
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,20}") String staffCode) {
}
