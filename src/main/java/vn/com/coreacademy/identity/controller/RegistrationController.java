package vn.com.coreacademy.identity.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.identity.dto.PasswordResetRequestForm;
import vn.com.coreacademy.identity.dto.RegisterRequest;
import vn.com.coreacademy.identity.dto.RegistrationResult;
import vn.com.coreacademy.identity.dto.RegistrationUnit;
import vn.com.coreacademy.identity.service.PasswordResetRequestService;
import vn.com.coreacademy.identity.service.RegistrationService;

/** The two public endpoints of self-registration; everything else about access requests needs a token. */
@RestController
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Registration", description = "Khách gửi yêu cầu đăng ký tài khoản từ màn đăng nhập.")
@StandardApiErrors
@RequiredArgsConstructor
public class RegistrationController {
    private final RegistrationService registration;
    private final PasswordResetRequestService passwordResets;

    @GetMapping("/registration-units")
    @SecurityRequirements
    @Operation(
        summary = "Danh sách đơn vị cho form đăng ký",
        description = "Công khai. Chỉ đơn vị đang hoạt động; trả id và tên đơn vị, không trả mã tổ chức hay phòng ban."
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<List<RegistrationUnit>>> units() {
        return ResponseEntity.ok(BaseResponse.ok(registration.units()));
    }

    @PostMapping("/register-request")
    @SecurityRequirements
    @Operation(
        summary = "Gửi yêu cầu đăng ký tài khoản",
        description = """
            Công khai, giới hạn theo IP và theo mã cán bộ (429 khi vượt).
            - Luôn trả cùng một thông báo, dù mã cán bộ chưa có, đã có yêu cầu chờ hay đã có tài khoản.
            - Mật khẩu chỉ lưu dạng băm và chỉ dùng khi quản trị viên duyệt.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<RegistrationResult>> register(@Valid @RequestBody RegisterRequest request,
        HttpServletRequest http) {
        return ResponseEntity.ok(BaseResponse.ok(registration.register(http.getRemoteAddr(), request)));
    }

    @PostMapping("/password-reset-request")
    @SecurityRequirements
    @Operation(
        summary = "Gửi yêu cầu cấp lại mật khẩu",
        description = """
            Công khai, giới hạn theo IP và theo mã cán bộ (429 khi vượt).
            - Luôn trả cùng một thông báo, dù mã cán bộ có tài khoản hay không, hay đã có yêu cầu chờ.
            - Quản trị viên xử lý từ danh sách yêu cầu và gửi mật khẩu tạm bằng kênh riêng.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<RegistrationResult>> passwordReset(
        @Valid @RequestBody PasswordResetRequestForm request, HttpServletRequest http) {
        return ResponseEntity.ok(BaseResponse.ok(passwordResets.request(http.getRemoteAddr(), request.staffCode())));
    }
}
