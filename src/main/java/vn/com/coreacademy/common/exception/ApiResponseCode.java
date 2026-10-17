package vn.com.coreacademy.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Module định nghĩa enum mã lỗi riêng theo contract này; common không phụ thuộc enum của module.
 */
public interface ApiResponseCode {
    /** Kết quả nghiệp vụ độc lập với HTTP status; mặc định danh mục module là lỗi. */
    default boolean isSuccess() {
        return false;
    }

    HttpStatus getHttpStatus();

    String getCode();

    String getMessage();

    String getTitle();
}
