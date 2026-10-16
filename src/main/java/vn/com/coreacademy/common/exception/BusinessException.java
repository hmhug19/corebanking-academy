package vn.com.coreacademy.common.exception;

import lombok.Getter;

/** A controlled application error; its response text comes only from the code catalog. */
@Getter
public class BusinessException extends RuntimeException {
    private final ApiResponseCode responseCode;
    public BusinessException(ApiResponseCode responseCode) {
        super(responseCode.getCode());
        if (!responseCode.getHttpStatus().isError()) throw new IllegalArgumentException("An error response code is required");
        this.responseCode = responseCode;
    }
}
