package vn.com.coreacademy.community.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import vn.com.coreacademy.common.exception.ApiResponseCode;

@Getter
@RequiredArgsConstructor
public enum CommunityResponseCode implements ApiResponseCode {
    NOT_FOUND("CORE-RESOURCE_NOT_FOUND", "Không tìm thấy nội dung trong phạm vi được phép."),
    CONFLICT("COMMUNITY-CONFLICT", "Dữ liệu đã thay đổi. Vui lòng tải lại trước khi lưu."),
    INVALID_REFERENCE("COMMUNITY-INVALID_REFERENCE", "Tham chiếu không hợp lệ hoặc không cùng phạm vi."),
    INVALID_STATE("COMMUNITY-STATE_INVALID", "Trạng thái hiện tại không cho phép thao tác này."),
    SELF_VOTE("COMMUNITY-SELF_VOTE", "Không được tự bình chọn nội dung của mình."),
    ACCEPT_INVALID("COMMUNITY-ACCEPT_INVALID", "Câu trả lời hoặc phiên bản chưa phù hợp để xác nhận."),
    ASSET_NOT_READY("COMMUNITY-ASSET_NOT_READY", "Tài nguyên chưa sẵn sàng hoặc không được phép công bố."),
    ASSET_INVALID("COMMUNITY-ASSET_INVALID", "Định dạng, nội dung hoặc kích thước tệp không hợp lệ."),
    UPLOAD_BUSY("COMMUNITY-UPLOAD_BUSY", "Phần tải lên đang xử lý. Hãy thử lại sau khi hết thời gian giữ chỗ."),
    FILE_MISMATCH("COMMUNITY-UPLOAD_FILE_MISMATCH", "Byte hoặc SHA-256 không khớp manifest đã khai báo."),
    NOT_PUBLISHABLE("COMMUNITY-NOT_PUBLISHABLE", "Nội dung chưa đủ điều kiện xuất bản."),
    DUPLICATE("COMMUNITY-DUPLICATE", "Định danh này đã được sử dụng."),
    IDEMPOTENCY_CONFLICT("COMMUNITY-IDEMPOTENCY_CONFLICT", "Mã yêu cầu đã được dùng với nội dung khác.");

    private final HttpStatus httpStatus = HttpStatus.OK;
    private final String code;
    private final String message;
    private final String title = "Chưa thể thực hiện";
}
