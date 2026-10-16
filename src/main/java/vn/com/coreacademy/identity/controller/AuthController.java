package vn.com.coreacademy.identity.controller;

import lombok.RequiredArgsConstructor;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import org.springframework.security.core.Authentication;
import vn.com.coreacademy.common.dto.BaseResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import vn.com.coreacademy.identity.constants.SecurityConstants;
import org.springframework.web.bind.annotation.*;
import vn.com.coreacademy.identity.dto.LoginRequest;
import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.TokenResponse;
import vn.com.coreacademy.identity.security.CurrentUser;
import vn.com.coreacademy.identity.service.AuthService;

@StandardApiErrors
@RestController
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication", description = "Đăng nhập và thông tin tài khoản hiện tại")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final CurrentUser currentUser;

    @ApiResponse(responseCode = "200", description = "Login successful", useReturnTypeSchema = true)
    @PostMapping("/login")
    @Operation(summary = "Đăng nhập", description = "Username nhận username hoặc mã cán bộ; trả JWT accessToken.")
    @SecurityRequirements
    public ResponseEntity<BaseResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.ok(authService.login(request)));
    }

    @ApiResponse(responseCode = "200", description = "All account tokens revoked", useReturnTypeSchema = true)
    @PostMapping("/logout")
    @Operation(summary = "Đăng xuất mọi phiên", description = "Vô hiệu hóa toàn bộ JWT đã phát hành của tài khoản bằng auth_ver. Trả BaseResponse với HTTP 200; frontend xóa token. Token thiếu, hết hạn hoặc đã bị thu hồi trả 401.")
    public ResponseEntity<BaseResponse<Void>> logout(@Parameter(hidden = true) Authentication authentication) {
        String userId = currentUser.id(authentication);
        Jwt jwt = (Jwt) authentication.getPrincipal();
        Number version = (Number) jwt.getClaims().get(SecurityConstants.AUTH_VERSION_CLAIM);
        authService.logout(userId, version.longValue());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.ok());
    }

    @ApiResponse(responseCode = "200", description = "Current account profile and rights", useReturnTypeSchema = true)
    @GetMapping("/me")
    @Operation(summary = "Thông tin tài khoản", description = "Trả hồ sơ, mã role/function, scope của từng role và thông tin màn hình. Quyền màn hình không thay thế kiểm tra hành động API.")
    public ResponseEntity<BaseResponse<MeResponse>> me(@Parameter(hidden = true) Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BaseResponse.ok(authService.me(currentUser.id(authentication))));
    }
}
