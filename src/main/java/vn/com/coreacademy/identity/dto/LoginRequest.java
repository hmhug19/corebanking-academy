package vn.com.coreacademy.identity.dto;

import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(
        @NotBlank @Schema(description = "Username hoặc mã cán bộ", example = "demo.user") String username,
        @NotBlank @Schema(description = "Mật khẩu đăng nhập", format = "password",
                accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
}
