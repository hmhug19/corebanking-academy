package vn.com.coreacademy.common.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.common.exception.ApiResponseCode;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.common.web.RequestIdContext;

/**
 * Envelope bất biến cho response nghiệp vụ; bên trả response vẫn phải đặt HTTP status tương ứng.
 */
public record BaseResponse<T>(
    @Schema(description = "ID đối chiếu log, khớp X-Request-Id khi đi qua RequestIdFilter.") String responseId,
    Instant timestamp,
    ResponseStatus status,
    String messageCode,
    String message,
    String messageTitle,
    @JsonInclude(JsonInclude.Include.ALWAYS) T data,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) List<FieldViolation> errors) {
    public BaseResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static <T> BaseResponse<T> ok(T data) {
        return create(ResponseStatus.SUCCESS, ResponseCode.SUCCESS, data, List.of());
    }

    public static BaseResponse<Void> ok() {
        return ok(null);
    }

    public static BaseResponse<Void> fail(ApiResponseCode code) {
        return fail(code, List.of());
    }

    public static BaseResponse<Void> fail(ApiResponseCode code, List<FieldViolation> errors) {
        if (code.isSuccess()) {
            throw new IllegalArgumentException("An error response code is required");
        }
        return create(ResponseStatus.ERROR, code, null, errors);
    }

    private static <T> BaseResponse<T> create(ResponseStatus status, ApiResponseCode code, T data,
                                              List<FieldViolation> errors) {
        // Một lỗi cụ thể có thể hiển thị trực tiếp; nhiều lỗi giữ thông báo tổng quát theo mã nghiệp vụ.
        String message = errors != null && errors.size() == 1 ? errors.get(0).message() : code.getMessage();
        return new BaseResponse<>(RequestIdContext.currentOrNew(), Instant.now(), status,
            code.getCode(), message, code.getTitle(), data, errors);
    }
}
