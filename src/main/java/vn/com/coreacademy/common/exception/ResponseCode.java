package vn.com.coreacademy.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ResponseCode implements ApiResponseCode {
    SUCCESS(HttpStatus.OK, "CORE-0000", "Request completed successfully", "Success"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "CORE-0400-VALIDATION", "Request validation failed", "Invalid request"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "CORE-0400-JSON", "Request body is missing or malformed", "Invalid request"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "CORE-0400", "Request rejected", "Invalid request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "CORE-0401", "Authentication failed or token is invalid", "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "CORE-0403", "You do not have permission to perform this action", "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "CORE-0404", "Requested resource was not found", "Not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "CORE-0405", "HTTP method is not supported", "Method not allowed"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "CORE-0406", "Requested response format is not supported", "Not acceptable"),
    CONFLICT(HttpStatus.CONFLICT, "CORE-0409", "Request conflicts with the current resource state", "Conflict"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "CORE-0415", "Request content type is not supported", "Unsupported media type"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "CORE-0429", "Too many requests", "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "CORE-0500", "An unexpected error occurred", "Server error");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
    private final String title;

    public static ResponseCode forHttpStatus(int status) {
        for (ResponseCode code : values()) if (code.httpStatus.value() == status && code != VALIDATION_FAILED && code != MALFORMED_REQUEST) return code;
        return status >= 500 ? INTERNAL_ERROR : BAD_REQUEST;
    }
}
