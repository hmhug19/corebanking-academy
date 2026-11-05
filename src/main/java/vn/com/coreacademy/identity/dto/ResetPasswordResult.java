package vn.com.coreacademy.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The resolved request plus the temporary password, returned once and never stored in clear. */
public record ResetPasswordResult(
    AccessRequestView request,
    @Schema(description = "Mật khẩu tạm, chỉ hiện một lần; người dùng phải đổi ở lần đăng nhập đầu.")
    String temporaryPassword) {

    @Override
    public String toString() {
        return "ResetPasswordResult[request=" + request + ", temporaryPassword=<redacted>]";
    }
}
