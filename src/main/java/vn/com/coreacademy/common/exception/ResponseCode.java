package vn.com.coreacademy.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ResponseCode implements ApiResponseCode {
    SUCCESS(HttpStatus.OK, "CORE-SUCCESS", "Thao tác thành công.", "Thành công"),
    VALIDATION_FAILED(HttpStatus.OK, "CORE-VALIDATION_FAILED",
        "Dữ liệu đầu vào không hợp lệ.", "Thông tin chưa hợp lệ"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "CORE-MALFORMED_REQUEST",
        "Nội dung yêu cầu bị thiếu hoặc JSON không đúng cú pháp.", "Yêu cầu không hợp lệ"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "CORE-BAD_REQUEST", "Yêu cầu không hợp lệ.", "Yêu cầu không hợp lệ"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "CORE-UNAUTHENTICATED",
        "Phiên đăng nhập không hợp lệ hoặc đã hết hiệu lực. Vui lòng đăng nhập lại.", "Yêu cầu đăng nhập"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "CORE-ACCESS_DENIED",
        "Bạn không có quyền thực hiện thao tác này.", "Không có quyền truy cập"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "CORE-ENDPOINT_NOT_FOUND",
        "Không tìm thấy tài nguyên yêu cầu.", "Không tìm thấy dữ liệu"),
    RESOURCE_NOT_FOUND(HttpStatus.OK, "CORE-RESOURCE_NOT_FOUND",
        "Không tìm thấy dữ liệu yêu cầu.", "Không tìm thấy dữ liệu"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "CORE-METHOD_NOT_ALLOWED",
        "Phương thức HTTP không được hỗ trợ.", "Phương thức không hợp lệ"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "CORE-NOT_ACCEPTABLE",
        "Định dạng phản hồi yêu cầu không được hỗ trợ.", "Định dạng không được hỗ trợ"),
    CONFLICT(HttpStatus.OK, "CORE-STATE_CONFLICT",
        "Thao tác không phù hợp với trạng thái dữ liệu hiện tại.", "Xung đột dữ liệu"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "CORE-UNSUPPORTED_MEDIA_TYPE",
        "Kiểu nội dung yêu cầu không được hỗ trợ.", "Kiểu nội dung không hợp lệ"),
    RANGE_NOT_SATISFIABLE(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "CORE-RANGE_NOT_SATISFIABLE",
        "Khoảng byte yêu cầu không hợp lệ hoặc không được hỗ trợ.", "Không thể tải phần nội dung yêu cầu"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "CORE-TOO_MANY_REQUESTS",
        "Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.", "Quá nhiều yêu cầu"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "CORE-INTERNAL_ERROR",
        "Hệ thống gặp lỗi. Vui lòng thử lại sau hoặc cung cấp responseId để được hỗ trợ.", "Lỗi hệ thống");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
    private final String title;

    @Override
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** Ánh xạ lỗi HTTP hạ tầng tường minh, không suy từ mã nghiệp vụ có thể dùng cùng HTTP 200. */
    public static ResponseCode forHttpStatus(int status) {
        return switch (status) {
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 416 -> RANGE_NOT_SATISFIABLE;
            case 429 -> TOO_MANY_REQUESTS;
            default -> status >= 500 ? INTERNAL_ERROR : BAD_REQUEST;
        };
    }
}
