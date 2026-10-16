package vn.com.coreacademy.common.exception;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.http.converter.HttpMessageNotReadableException;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.FieldViolation;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<BaseResponse<Void>> forbidden(AccessDeniedException exception) { return error(ResponseCode.FORBIDDEN); }
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<BaseResponse<Void>> unauthorized(AuthenticationException exception) { return error(ResponseCode.UNAUTHORIZED); }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<Void>> business(BusinessException exception) { return error(exception.getResponseCode()); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<BaseResponse<Void>> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).headers(exception.getHeaders())
                .contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(BaseResponse.fail(ResponseCode.forHttpStatus(exception.getStatusCode().value())));
    }
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldViolation(e.getField(), e.getCode(), e.getDefaultMessage())).distinct().toList();
        return handleExceptionInternal(exception, BaseResponse.fail(ResponseCode.VALIDATION_FAILED, fields), headers, status, request);
    }
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(exception, BaseResponse.fail(ResponseCode.MALFORMED_REQUEST), headers, status, request);
    }
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<BaseResponse<Void>> constraint(ConstraintViolationException exception) {
        // Do not echo invalid values or provider-generated messages that might contain credentials.
        var fields = exception.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), "ConstraintViolation", "Invalid value")).toList();
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(BaseResponse.fail(ResponseCode.VALIDATION_FAILED, fields));
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Object envelope = body instanceof BaseResponse<?> ? body : BaseResponse.fail(ResponseCode.forHttpStatus(status.value()));
        HttpHeaders safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setCacheControl(CacheControl.noStore());
        safeHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception, envelope, safeHeaders, status, request);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> unexpected(Exception exception) {
        // Exception messages/causes can contain request values. Log location without echoing them.
        log.error("Unhandled API exception type={} stack={}", exception.getClass().getName(),
                java.util.Arrays.toString(exception.getStackTrace()));
        return error(ResponseCode.INTERNAL_ERROR);
    }
    private ResponseEntity<BaseResponse<Void>> error(ApiResponseCode code) {
        return ResponseEntity.status(code.getHttpStatus()).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(BaseResponse.fail(code));
    }
}
