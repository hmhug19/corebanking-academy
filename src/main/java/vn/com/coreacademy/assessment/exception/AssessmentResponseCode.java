package vn.com.coreacademy.assessment.exception;

import java.util.Locale;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.common.exception.ApiResponseCode;

@Getter
@RequiredArgsConstructor
public enum AssessmentResponseCode implements ApiResponseCode {
    QUIZ_REVISION_FROZEN("ASSESSMENT-QUIZ_REVISION_FROZEN",
        "Bài kiểm tra đang thuộc phiên bản khóa học đã xuất bản. Hãy tạo đề mới để thay đổi nội dung; "
            + "chỉ đóng đề cũ khi không còn phiên bản hiện hành hoặc lần học dở cần sử dụng.", "Cần giữ bài kiểm tra cũ"),
    NOT_FOUND("ASSESSMENT-NOT_FOUND", "Không tìm thấy dữ liệu kiểm tra phù hợp.", "Không tìm thấy"),
    CONFLICT("ASSESSMENT-CONFLICT", "Dữ liệu đã thay đổi hoặc xung đột. Vui lòng tải lại và kiểm tra.",
        "Xung đột dữ liệu"),
    DUPLICATE("ASSESSMENT-DUPLICATE", "Mã hoặc đối tượng này đã được sử dụng.", "Dữ liệu đã tồn tại"),
    INVALID_REFERENCE("ASSESSMENT-INVALID_REFERENCE", "ID tham chiếu không tồn tại hoặc không đúng quan hệ.",
        "Tham chiếu chưa hợp lệ"),
    QUIZ_NOT_READY("ASSESSMENT-QUIZ_NOT_READY",
        "Bài kiểm tra chưa đủ câu hỏi đang sử dụng so với số câu cần phát.", "Bài kiểm tra chưa sẵn sàng"),
    QUIZ_NOT_OPEN("ASSESSMENT-QUIZ_NOT_OPEN", "Bài kiểm tra chưa đến thời gian mở.", "Chưa thể làm bài"),
    QUIZ_CLOSED("ASSESSMENT-QUIZ_CLOSED", "Bài kiểm tra đã đóng.", "Chưa thể làm bài"),
    LESSONS_REQUIRED("ASSESSMENT-LESSONS_REQUIRED", "Bạn cần hoàn thành các bài học bắt buộc trước khi làm bài.",
        "Chưa thể làm bài"),
    ALREADY_PASSED("ASSESSMENT-ALREADY_PASSED", "Bạn đã đạt bài kiểm tra này.", "Chưa thể làm bài"),
    NO_ATTEMPTS_LEFT("ASSESSMENT-NO_ATTEMPTS_LEFT",
        "Bạn đã dùng hết lượt thi. Liên hệ giảng viên để được xem xét cấp thêm lượt.", "Chưa thể làm bài"),
    RETAKE_WAIT("ASSESSMENT-RETAKE_WAIT", "Chưa hết thời gian chờ giữa hai lượt thi.", "Chưa thể làm bài"),
    ATTEMPT_CLOSED("ASSESSMENT-ATTEMPT_CLOSED", "Lượt thi đã nộp hoặc đã hết giờ, không thể sửa đáp án.",
        "Chưa thể lưu"),
    REVIEW_NOT_AVAILABLE("ASSESSMENT-REVIEW_NOT_AVAILABLE",
        "Đề thi chưa cho xem đáp án ở thời điểm này nên chưa giải thích được câu hỏi.", "Chưa xem được đáp án"),
    ATTEMPT_IN_PROGRESS("ASSESSMENT-ATTEMPT_IN_PROGRESS", "Lượt thi chưa được nộp nên chưa có kết quả.",
        "Chưa có kết quả"),
    QUESTION_IN_USE("ASSESSMENT-QUESTION_IN_USE",
        "Câu hỏi đang được dùng trong bài kiểm tra; chỉ có thể ngưng sử dụng.", "Chưa thể xóa"),
    QUIZ_IN_USE("ASSESSMENT-QUIZ_IN_USE", "Bài kiểm tra đã có lượt thi hoặc đang gắn vào khóa học; không thể xóa.",
        "Chưa thể xóa"),
    COMPETENCY_IN_USE("ASSESSMENT-COMPETENCY_IN_USE",
        "Nhóm năng lực đang được gắn với câu hỏi hoặc có nhóm con; chỉ có thể ngưng sử dụng.", "Chưa thể xóa"),
    IMPORT_INVALID("ASSESSMENT-IMPORT_INVALID",
        "Tệp nhập có lỗi; chưa dòng nào được ghi. Vui lòng sửa theo danh sách lỗi.", "Tệp nhập chưa hợp lệ"),
    CHECKPOINT_STALE("ASSESSMENT-CHECKPOINT_STALE",
        "Câu hỏi nhanh đã thay đổi hoặc không còn dùng cho bài học này. Hãy tải lại các câu hỏi.",
        "Câu hỏi đã thay đổi"),
    EXPORT_TOO_LARGE("ASSESSMENT-EXPORT_TOO_LARGE", "Kết quả vượt giới hạn xuất %s dòng. Vui lòng thu hẹp bộ lọc."
            .formatted(String.format(Locale.ROOT, "%,d", AssessmentLimits.MAX_EXPORT_ROWS).replace(',', '.')),
        "Chưa thể xuất");

    private final HttpStatus httpStatus = HttpStatus.OK;
    private final String code;
    private final String message;
    private final String title;
}
