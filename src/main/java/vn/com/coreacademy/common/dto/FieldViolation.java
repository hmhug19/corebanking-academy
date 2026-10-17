package vn.com.coreacademy.common.dto;

/**
 * Không đưa giá trị bị từ chối vào lỗi vì field có thể chứa mật khẩu hoặc token.
 */
public record FieldViolation(String field, String code, String message) {
}
