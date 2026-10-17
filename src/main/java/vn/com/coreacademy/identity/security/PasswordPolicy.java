package vn.com.coreacademy.identity.security;

import java.nio.charset.StandardCharsets;

import vn.com.coreacademy.common.exception.BusinessException;

public final class PasswordPolicy {
    private PasswordPolicy() {
    }

    /**
     * Kiểm tra tối thiểu 6 ký tự theo độ dài String và tối đa 72 byte UTF-8 để tránh vượt giới hạn BCrypt.
     * Không tự cắt hoặc chuẩn hóa mật khẩu vì sẽ thay đổi giá trị người dùng đã nhập.
     */
    public static void validate(String password, String field) {
        if (password == null || password.length() < 6 || password.isBlank()
            || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw BusinessException.validation(field, "PASSWORD_POLICY",
                "Mật khẩu phải có ít nhất 6 ký tự, không chỉ gồm khoảng trắng và không vượt quá 72 byte UTF-8.");
        }
    }
}
