package vn.com.coreacademy.common.validation;

import vn.com.coreacademy.common.dto.FieldViolation;

/** Ánh xạ constraint nội bộ sang mã ổn định và thông báo an toàn, không dùng rejectedValue/message từ exception. */
public final class ValidationErrors {
    private ValidationErrors() {
    }

    public static FieldViolation fromConstraint(String field, String constraint) {
        String code = switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Email", "Pattern" -> "INVALID_FORMAT";
            case "Min", "Max", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
                 "DecimalMin", "DecimalMax" -> "OUT_OF_RANGE";
            case "Size", "Length" -> "INVALID_LENGTH";
            default -> "INVALID_VALUE";
        };
        String message = switch (code) {
            case "REQUIRED" -> "Thông tin này là bắt buộc.";
            case "INVALID_FORMAT" -> "Thông tin không đúng định dạng.";
            case "OUT_OF_RANGE" -> "Giá trị nằm ngoài phạm vi cho phép.";
            case "INVALID_LENGTH" -> "Độ dài hoặc số lượng phần tử không hợp lệ.";
            default -> "Giá trị không hợp lệ.";
        };
        return new FieldViolation(field, code, message);
    }
}
