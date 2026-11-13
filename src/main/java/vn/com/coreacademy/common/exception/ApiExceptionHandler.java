package vn.com.coreacademy.common.exception;

import java.util.List;
import java.util.Arrays;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.FieldViolation;
import vn.com.coreacademy.common.validation.ValidationErrors;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<BaseResponse<Void>> forbidden(AccessDeniedException exception, HttpServletRequest request) {
        log.warn("Handled API security error method={} path={} messageCode={}", request.getMethod(),
            request.getRequestURI(), ResponseCode.FORBIDDEN.getCode());
        return error(ResponseCode.FORBIDDEN);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<BaseResponse<Void>> unauthorized(AuthenticationException exception,
                                                             HttpServletRequest request) {
        if (exception instanceof AuthenticationServiceException) {
            return internalError(exception, request);
        }
        log.warn("Handled API authentication error method={} path={} messageCode={}", request.getMethod(),
            request.getRequestURI(), ResponseCode.UNAUTHORIZED.getCode());
        return error(ResponseCode.UNAUTHORIZED);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<BaseResponse<Void>> business(BusinessException exception, HttpServletRequest request) {
        log.warn("Handled API business error method={} path={} httpStatus={} messageCode={} errorCount={}",
            request.getMethod(), request.getRequestURI(), exception.getResponseCode().getHttpStatus().value(),
            exception.getResponseCode().getCode(), exception.getErrors().size());
        return ResponseEntity.status(exception.getResponseCode().getHttpStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .body(BaseResponse.fail(exception.getResponseCode(), exception.getErrors()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<BaseResponse<Void>> status(ResponseStatusException exception) {
        log.warn("Handled API HTTP error httpStatus={} messageCode={}", exception.getStatusCode().value(),
            ResponseCode.forHttpStatus(exception.getStatusCode().value()).getCode());
        return ResponseEntity.status(exception.getStatusCode()).headers(exception.getHeaders())
            .contentType(MediaType.APPLICATION_JSON)
            .body(BaseResponse.fail(ResponseCode.forHttpStatus(exception.getStatusCode().value())));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> fields = exception.getBindingResult().getFieldErrors().stream()
            .map(e -> ValidationErrors.fromConstraint(e.getField(), e.getCode())).distinct().toList();
        return handleExceptionInternal(exception, BaseResponse.fail(ResponseCode.VALIDATION_FAILED, fields), headers,
            HttpStatus.OK, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof UnsupportedFieldException unsupported) {
            return validation(exception, headers, request, new FieldViolation(safeField(unsupported.getField()),
                "UNSUPPORTED_FIELD", "Trường này không được phép cập nhật."));
        }
        if (!(cause instanceof JsonParseException)
            && exception.getCause() instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            return validation(exception, headers, request, new FieldViolation(jsonFieldPath(mapping),
                "INVALID_FORMAT", "Kiểu dữ liệu hoặc giá trị không hợp lệ."));
        }
        return handleExceptionInternal(exception, BaseResponse.fail(ResponseCode.MALFORMED_REQUEST), headers, status,
            request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<BaseResponse<Void>> constraint(ConstraintViolationException exception) {
        // Không trả giá trị sai hoặc thông báo tự sinh có thể chứa mật khẩu/thông tin nhạy cảm.
        var fields = exception.getConstraintViolations().stream()
            .map(v -> ValidationErrors.fromConstraint(v.getPropertyPath().toString(),
                v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()))
            .toList();
        log.info("Handled API validation error messageCode={} errorCount={}",
            ResponseCode.VALIDATION_FAILED.getCode(), fields.size());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
            .body(BaseResponse.fail(ResponseCode.VALIDATION_FAILED, fields));
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
        HttpStatusCode status, WebRequest request) {
        String field = exception instanceof MethodArgumentTypeMismatchException argument ? argument.getName() : "";
        return validation(exception, headers, request,
            new FieldViolation(field, "INVALID_FORMAT", "Tham số không đúng kiểu dữ liệu hoặc định dạng."));
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
        MissingServletRequestParameterException exception, HttpHeaders headers, HttpStatusCode status,
        WebRequest request) {
        return validation(exception, headers, request,
            ValidationErrors.fromConstraint(exception.getParameterName(), "NotNull"));
    }

    private ResponseEntity<Object> validation(Exception exception, HttpHeaders headers, WebRequest request,
                                              FieldViolation field) {
        return handleExceptionInternal(exception, BaseResponse.fail(ResponseCode.VALIDATION_FAILED, List.of(field)),
            headers, HttpStatus.OK, request);
    }

    private String jsonFieldPath(JsonMappingException mapping) {
        StringBuilder field = new StringBuilder();
        for (var part : mapping.getPath()) {
            if (part.getFieldName() != null) {
                if (!field.isEmpty()) {
                    field.append('.');
                }
                field.append(part.getFieldName());
            } else if (part.getIndex() >= 0) {
                field.append('[').append(part.getIndex()).append(']');
            }
        }
        return safeField(field.toString());
    }

    private String safeField(String field) {
        return field.matches("[A-Za-z0-9_.\\[\\]-]{1,200}") ? field : "";
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, @Nullable Object body,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Object envelope = body instanceof BaseResponse<?> ? body
            : BaseResponse.fail(ResponseCode.forHttpStatus(status.value()));
        if (envelope instanceof BaseResponse<?> apiResponse && apiResponse.status().name().equals("ERROR")) {
            String method = request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getMethod() : "UNKNOWN";
            String path = request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI() : "UNKNOWN";
            log.info("Handled API request error method={} path={} httpStatus={} messageCode={} exceptionType={}",
                method, path, status.value(), apiResponse.messageCode(), exception.getClass().getName());
        }
        HttpHeaders safeHeaders = new HttpHeaders();
        safeHeaders.putAll(headers);
        safeHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception, envelope, safeHeaders, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<BaseResponse<Void>> unexpected(Exception exception, HttpServletRequest request,
                                                           HttpServletResponse response) {
        if (response.isCommitted()) {
            // Không ghép JSON lỗi vào binary đã bắt đầu; Content-Length cho client nhận biết tải chưa đủ.
            log.warn("Response stream interrupted method={} path={} exceptionType={} stack={}", request.getMethod(),
                request.getRequestURI(), exception.getClass().getName(), Arrays.toString(exception.getStackTrace()));
            return null;
        }
        response.resetBuffer();
        response.setHeader(HttpHeaders.CONTENT_LENGTH, null);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, null);
        response.setHeader(HttpHeaders.CONTENT_RANGE, null);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, null);
        response.setHeader(HttpHeaders.ETAG, null);
        return internalError(exception, request);
    }

    private ResponseEntity<BaseResponse<Void>> internalError(Exception exception, HttpServletRequest request) {
        // Message/cause có thể chứa dữ liệu request; chỉ log loại exception và vị trí lỗi.
        log.error("Unhandled API exception method={} path={} exceptionType={} stack={}", request.getMethod(),
            request.getRequestURI(), exception.getClass().getName(), Arrays.toString(exception.getStackTrace()));
        return error(ResponseCode.INTERNAL_ERROR);
    }

    private ResponseEntity<BaseResponse<Void>> error(ApiResponseCode code) {
        return ResponseEntity.status(code.getHttpStatus()).contentType(MediaType.APPLICATION_JSON)
            .body(BaseResponse.fail(code));
    }
}
