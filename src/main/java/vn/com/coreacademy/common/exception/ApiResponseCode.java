package vn.com.coreacademy.common.exception;

import org.springframework.http.HttpStatus;

/** Modules may implement this contract in their own response-code enums. */
public interface ApiResponseCode {
    HttpStatus getHttpStatus();
    String getCode();
    String getMessage();
    String getTitle();
}
