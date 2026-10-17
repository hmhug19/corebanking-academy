package vn.com.coreacademy.identity.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import vn.com.coreacademy.common.exception.ApiResponseCode;

@Getter
@RequiredArgsConstructor
public enum IdentityResponseCode implements ApiResponseCode {
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "IDENTITY-CREDENTIALS_INVALID",
        "Tên đăng nhập hoặc mật khẩu không đúng.", "Đăng nhập không thành công"),
    PASSWORD_CHANGE_REQUIRED(HttpStatus.FORBIDDEN, "IDENTITY-PASSWORD_CHANGE_REQUIRED",
        "Vui lòng đổi mật khẩu tạm trước khi tiếp tục.", "Yêu cầu đổi mật khẩu"),
    CURRENT_PASSWORD_INCORRECT(HttpStatus.OK, "IDENTITY-CURRENT_PASSWORD_INCORRECT",
        "Mật khẩu hiện tại không đúng.", "Không thể đổi mật khẩu"),
    DUPLICATE_IDENTIFIER(HttpStatus.OK, "IDENTITY-IDENTIFIER_EXISTS",
        "Mã cán bộ hoặc tên đăng nhập đã được sử dụng.", "Trùng thông tin tài khoản"),
    INVALID_MEMBERSHIP(HttpStatus.OK, "IDENTITY-MEMBERSHIP_INVALID",
        "Tổ chức hoặc nhóm không hợp lệ, ngừng hoạt động hoặc không khớp nhau.", "Thông tin tổ chức chưa hợp lệ"),
    LAST_SUPER_ADMIN(HttpStatus.OK, "IDENTITY-LAST_SUPER_ADMIN",
        "Hệ thống phải còn ít nhất một SUPER_ADMIN đang hoạt động với phạm vi GLOBAL.", "Cần giữ quản trị viên gốc"),
    DUPLICATE_GRANT(HttpStatus.OK, "IDENTITY-ROLE_GRANT_EXISTS",
        "Phân quyền cho vai trò và phạm vi này đang tồn tại.", "Trùng phân quyền"),
    INVALID_SCOPE(HttpStatus.OK, "IDENTITY-SCOPE_INVALID",
        "Vai trò hoặc phạm vi phân quyền không hợp lệ.", "Phân quyền chưa hợp lệ");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
    private final String title;
}
