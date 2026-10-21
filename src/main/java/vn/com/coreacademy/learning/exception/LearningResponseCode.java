package vn.com.coreacademy.learning.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import vn.com.coreacademy.common.exception.ApiResponseCode;

@Getter
@RequiredArgsConstructor
public enum LearningResponseCode implements ApiResponseCode {
    NOT_FOUND("LEARNING-NOT_FOUND", "Không tìm thấy dữ liệu học tập phù hợp.", "Không tìm thấy"),
    CONFLICT("LEARNING-CONFLICT", "Dữ liệu đã thay đổi hoặc xung đột. Vui lòng tải lại và kiểm tra.",
        "Xung đột dữ liệu"),
    DUPLICATE("LEARNING-DUPLICATE", "Mã hoặc đối tượng này đã được sử dụng.", "Dữ liệu đã tồn tại"),
    INVALID_REFERENCE("LEARNING-INVALID_REFERENCE", "ID tham chiếu không tồn tại hoặc không đúng quan hệ.",
        "Tham chiếu chưa hợp lệ"),
    NOT_PUBLISHABLE("LEARNING-NOT_PUBLISHABLE", "Khóa học chưa đủ nội dung hoặc tài nguyên để xuất bản.",
        "Chưa thể xuất bản"),
    NOT_AVAILABLE("LEARNING-NOT_AVAILABLE", "Khóa học chưa mở hoặc hiện không được phép học.", "Chưa thể học"),
    EMPTY_TARGET("LEARNING-EMPTY_TARGET", "Đối tượng giao không có học viên hợp lệ hoặc vượt giới hạn 500 người.",
        "Đối tượng chưa hợp lệ"),
    QUIZ_UNAVAILABLE("LEARNING-QUIZ_UNAVAILABLE", "Module kiểm tra chưa được triển khai; chưa thể cấu hình bài quiz.",
        "Chưa hỗ trợ kiểm tra"),
    ASSET_INVALID("LEARNING-ASSET_INVALID", "Tệp không hợp lệ, sai loại, vượt dung lượng hoặc thiếu metadata.",
        "Tài nguyên chưa hợp lệ");

    private final HttpStatus httpStatus = HttpStatus.OK;
    private final String code;
    private final String message;
    private final String title;
}
