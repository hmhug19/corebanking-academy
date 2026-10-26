package vn.com.coreacademy.common.exception;

import java.util.List;

import lombok.Getter;
import vn.com.coreacademy.common.dto.FieldViolation;

/** Lỗi nghiệp vụ có kiểm soát; không chứa giá trị đầu vào hoặc exception message để trả client. */
@Getter
public class BusinessException extends RuntimeException {
    private final ApiResponseCode responseCode;
    private final List<FieldViolation> errors;

    public BusinessException(ApiResponseCode responseCode) {
        this(responseCode, List.of());
    }

    public BusinessException(ApiResponseCode responseCode, List<FieldViolation> errors) {
        super(responseCode.getCode());
        if (responseCode.isSuccess()) {
            throw new IllegalArgumentException("An error response code is required");
        }
        this.responseCode = responseCode;
        this.errors = List.copyOf(errors);
    }

    public static BusinessException field(ApiResponseCode responseCode, String field, String code, String message) {
        return new BusinessException(responseCode, List.of(new FieldViolation(field, code, message)));
    }

    public static BusinessException validation(String field, String code, String message) {
        return field(ResponseCode.VALIDATION_FAILED, field, code, message);
    }

    /** Giữ mã nghiệp vụ khi gắn lỗi của đối tượng con vào đúng phần tử request cha. */
    public BusinessException withFieldPrefix(String prefix) {
        if (errors.isEmpty()) {
            return this;
        }
        List<FieldViolation> nested = errors.stream()
            .map(error -> new FieldViolation(error.field().isEmpty() ? prefix : prefix + "." + error.field(),
                error.code(), error.message())).toList();
        return new BusinessException(responseCode, nested);
    }
}
