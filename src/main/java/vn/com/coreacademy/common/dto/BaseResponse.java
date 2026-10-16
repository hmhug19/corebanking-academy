package vn.com.coreacademy.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.common.exception.ApiResponseCode;
import vn.com.coreacademy.common.web.RequestIdContext;
import java.time.Instant;
import java.util.List;

/** Immutable, typed envelope. HTTP status remains the transport-level result. */
public record BaseResponse<T>(
        @Schema(description = "Matches X-Request-Id response header") String responseId,
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
    public static BaseResponse<Void> ok() { return ok(null); }
    public static BaseResponse<Void> fail(ApiResponseCode code) { return fail(code, List.of()); }
    public static BaseResponse<Void> fail(ApiResponseCode code, List<FieldViolation> errors) {
        if (!code.getHttpStatus().isError()) throw new IllegalArgumentException("An error response code is required");
        return create(ResponseStatus.ERROR, code, null, errors);
    }
    private static <T> BaseResponse<T> create(ResponseStatus status, ApiResponseCode code, T data, List<FieldViolation> errors) {
        return new BaseResponse<>(RequestIdContext.currentOrNew(), Instant.now(), status,
                code.getCode(), code.getMessage(), code.getTitle(), data, errors);
    }
}
