package vn.com.coreacademy.identity.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.identity.dto.LoginRequest;
import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.PasswordChangeRequest;
import vn.com.coreacademy.identity.dto.ProfileUpdateRequest;
import vn.com.coreacademy.identity.dto.TokenResponse;
import vn.com.coreacademy.identity.service.AuthService;
import vn.com.coreacademy.identity.service.SelfProfileService;

@RestController
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication", description = "Xác thực, phiên đăng nhập và hồ sơ của tài khoản hiện tại.")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final SelfProfileService profiles;

    @ApiResponse(responseCode = "200",
        description = "SUCCESS trả token; ERROR trả lỗi validation theo field.", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "400",
        description = "Nội dung yêu cầu bị thiếu hoặc JSON không đúng cú pháp.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
    @ApiResponse(responseCode = "500",
        description = "Lỗi hệ thống; cung cấp responseId để hỗ trợ tra cứu.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
    @ApiResponse(responseCode = "401",
        description = "Sai đăng nhập: IDENTITY-CREDENTIALS_INVALID; sai token: CORE-UNAUTHENTICATED.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
    @PostMapping("/login")
    @Operation(
        summary = "Đăng nhập hệ thống",
        description = """
            Xác thực bằng tên đăng nhập hoặc mã cán bộ và trả token truy cập.
            - Chưa hỗ trợ đăng nhập bằng email.
            - Sau đăng nhập, gọi `GET /api/v1/auth/me` để lấy hồ sơ và quyền hiện tại.
            """
    )
    @SecurityRequirements
    public ResponseEntity<BaseResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(BaseResponse.ok(authService.login(request)));
    }

    @StandardApiErrors
    @ApiResponse(responseCode = "200",
        description = "Đăng xuất thành công; phiên của token này đã bị thu hồi.", useReturnTypeSchema = true)
    @PostMapping("/logout")
    @Operation(
        summary = "Đăng xuất phiên hiện tại",
        description = """
            Kết thúc phiên đăng nhập của token này (claim sid); phiên ở trình duyệt khác vẫn dùng được. Không cần body.
            - Yêu cầu token còn hiệu lực.
            - Phòng học và trình chạy SCORM mở từ phiên này cũng dừng.
            - Token phát trước khi có sid: đăng xuất thu hồi mọi phiên của tài khoản (auth_ver).
            - Frontend xóa token khi thành công hoặc khi nhận HTTP 401.
            """
    )
    public ResponseEntity<BaseResponse<Void>> logout() {
        authService.logout();
        return ResponseEntity.ok(BaseResponse.ok());
    }

    @StandardApiErrors
    @ApiResponse(responseCode = "200",
        description = "Trả hồ sơ và thông tin phân quyền hiện tại.", useReturnTypeSchema = true)
    @GetMapping("/me")
    @Operation(
        summary = "Lấy hồ sơ và quyền của tài khoản hiện tại",
        description = """
            Trả hồ sơ, tổ chức, nhóm và các vai trò/chức năng kèm phạm vi dữ liệu.
            - Nếu `mustChangePassword=true`, quyền được trả rỗng và người dùng cần đổi mật khẩu.
            - Quyền hiển thị không thay thế kiểm tra quyền tại backend.
            """
    )
    public ResponseEntity<BaseResponse<MeResponse>> me() {
        return ResponseEntity.ok(BaseResponse.ok(authService.me()));
    }

    @StandardApiErrors
    @PatchMapping("/me")
    @Operation(
        summary = "Cập nhật hồ sơ cá nhân",
        description = """
            Cập nhật hồ sơ của tài khoản nội bộ (LOCAL).
            - Cần hoàn tất đổi mật khẩu tạm trước khi sử dụng.
            - Không thay đổi tổ chức, nhóm hoặc phân quyền; tài khoản SSO không được chỉnh sửa.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<MeResponse>> updateMe(@Valid @RequestBody ProfileUpdateRequest request) {
        profiles.update(request);
        return ResponseEntity.ok(BaseResponse.ok(authService.me()));
    }

    @StandardApiErrors
    @PutMapping("/password")
    @Operation(
        summary = "Đổi mật khẩu tài khoản hiện tại",
        description = """
            Đổi mật khẩu của tài khoản nội bộ (LOCAL).
            - Thành công sẽ hoàn tất yêu cầu đổi mật khẩu tạm và thu hồi mọi phiên đăng nhập.
            - Frontend xóa token và yêu cầu người dùng đăng nhập lại.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<Void>> password(@Valid @RequestBody PasswordChangeRequest request) {
        profiles.password(request);
        return ResponseEntity.ok(BaseResponse.ok());
    }
}
