package vn.com.coreacademy.learning.exception;

import java.util.Locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import vn.com.coreacademy.common.exception.ApiResponseCode;
import vn.com.coreacademy.learning.constants.LearningLimits;

@Getter
@RequiredArgsConstructor
public enum LearningResponseCode implements ApiResponseCode {
    SESSION_IN_USE("LEARNING-SESSION_IN_USE", "Việc học đang mở ở nơi khác. Hãy xác nhận chuyển sang thiết bị này.",
        "Chưa thể thực hiện"),
    SESSION_EXPIRED("LEARNING-SESSION_EXPIRED",
        "Phiên học đã hết hạn hoặc được chuyển sang nơi khác. Vui lòng mở lại bài.",
        "Chưa thể thực hiện"),
    STALE_EVENT("LEARNING-STALE_EVENT", "Sự kiện tiến độ đã cũ hoặc không đúng thứ tự. Vui lòng đồng bộ lại.",
        "Chưa thể thực hiện"),
    MEDIA_CHANGED("LEARNING-MEDIA_CHANGED", "Tài nguyên không khớp phiên học. Vui lòng tải lại bài.",
        "Chưa thể thực hiện"),
    UPLOAD_FILE_MISMATCH("LEARNING-UPLOAD_FILE_MISMATCH", "File hoặc phần tải lên không khớp thông tin đã khai báo.",
        "Chưa thể thực hiện"),
    UPLOAD_EXPIRED("LEARNING-UPLOAD_EXPIRED", "Phiên tải lên đã hết hạn hoặc bị hủy.", "Chưa thể thực hiện"),
    UPLOAD_PART_CONFLICT("LEARNING-UPLOAD_PART_CONFLICT", "Phần tải lên đã tồn tại với nội dung khác.",
        "Chưa thể thực hiện"),
    ASSET_PROCESSING("LEARNING-ASSET_PROCESSING", "Tài nguyên đang được xử lý. Vui lòng chờ và thử lại.",
        "Chưa thể thực hiện"),
    ASSET_CODEC_UNSUPPORTED("LEARNING-ASSET_CODEC_UNSUPPORTED", "Định dạng hoặc codec video chưa được hỗ trợ.",
        "Chưa thể thực hiện"),
    ASSET_IN_USE("LEARNING-ASSET_IN_USE", "Tài nguyên còn được sử dụng trong nội dung học tập.", "Chưa thể thực hiện"),
    EXPORT_TOO_LARGE("LEARNING-EXPORT_TOO_LARGE", "Kết quả vượt giới hạn xuất %s dòng. Vui lòng thu hẹp bộ lọc."
            .formatted(String.format(Locale.ROOT, "%,d", LearningLimits.MAX_EXPORT_ROWS).replace(',', '.')),
        "Chưa thể thực hiện"),
    NOT_FOUND("LEARNING-NOT_FOUND", "Không tìm thấy dữ liệu học tập phù hợp.", "Không tìm thấy"),
    CONFLICT("LEARNING-CONFLICT", "Dữ liệu đã thay đổi hoặc xung đột. Vui lòng tải lại và kiểm tra.",
        "Xung đột dữ liệu"),
    DUPLICATE("LEARNING-DUPLICATE", "Mã hoặc đối tượng này đã được sử dụng.", "Dữ liệu đã tồn tại"),
    INVALID_REFERENCE("LEARNING-INVALID_REFERENCE", "ID tham chiếu không tồn tại hoặc không đúng quan hệ.",
        "Tham chiếu chưa hợp lệ"),
    NOT_PUBLISHABLE("LEARNING-NOT_PUBLISHABLE", "Khóa học chưa đủ nội dung hoặc tài nguyên để xuất bản.",
        "Chưa thể xuất bản"),
    NOT_AVAILABLE("LEARNING-NOT_AVAILABLE", "Khóa học chưa mở hoặc hiện không được phép học.", "Chưa thể học"),
    EMPTY_TARGET("LEARNING-EMPTY_TARGET", "Đối tượng giao không có học viên hợp lệ hoặc vượt giới hạn %d người."
            .formatted(LearningLimits.MAX_LEARNERS_PER_ASSIGNMENT),
        "Đối tượng chưa hợp lệ"),
    LEADERBOARD_TOO_LARGE("LEARNING-LEADERBOARD_TOO_LARGE",
        "Phạm vi xếp hạng vượt giới hạn %d học viên. Vui lòng chọn phạm vi hẹp hơn."
            .formatted(LearningLimits.MAX_LEADERBOARD_LEARNERS), "Chưa thể xếp hạng"),
    QUIZ_UNAVAILABLE("LEARNING-QUIZ_UNAVAILABLE", "Module kiểm tra chưa được triển khai; chưa thể cấu hình bài quiz.",
        "Chưa hỗ trợ kiểm tra"),
    QUIZ_INVALID("LEARNING-QUIZ_INVALID",
        "Bài kiểm tra không tồn tại, đã bị xóa hoặc không thuộc khóa học này.", "Bài kiểm tra chưa hợp lệ"),
    QUIZ_LESSON_UNLINKED("LEARNING-QUIZ_LESSON_UNLINKED",
        "Có bài học loại kiểm tra chưa chọn bài kiểm tra đã xuất bản của khóa học.", "Chưa thể xuất bản"),
    ASSET_STORAGE_UNSUPPORTED("LEARNING-ASSET_STORAGE_UNSUPPORTED",
        "Tài nguyên chưa được chuyển sang kho S3. Vui lòng liên hệ quản trị viên.", "Tài nguyên chưa sẵn sàng"),
    ASSET_INVALID("LEARNING-ASSET_INVALID", "Tệp không hợp lệ, sai loại, vượt dung lượng hoặc thiếu metadata.",
        "Tài nguyên chưa hợp lệ");

    private final HttpStatus httpStatus = HttpStatus.OK;
    private final String code;
    private final String message;
    private final String title;
}
