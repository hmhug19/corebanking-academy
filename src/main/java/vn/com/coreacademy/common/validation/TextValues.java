package vn.com.coreacademy.common.validation;

import vn.com.coreacademy.common.exception.BusinessException;

public final class TextValues {
    private TextValues() {
    }

    public static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw BusinessException.validation(field, "REQUIRED", "Thông tin này là bắt buộc.");
        }
        return value.trim();
    }
}
