package vn.com.coreacademy.identity.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vn.com.coreacademy.identity.dto.LoginRequest;
import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.TokenResponse;
import vn.com.coreacademy.identity.security.CurrentUser;
import vn.com.coreacademy.identity.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Đăng nhập và thông tin tài khoản hiện tại")
public class AuthController {
    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/login")
    @Operation(summary = "Đăng nhập", description = "Username nhận username hoặc mã cán bộ; trả JWT accessToken.")
    @SecurityRequirements
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Thông tin tài khoản", description = "Trả hồ sơ, vai trò, quyền và phạm vi tài khoản hiện tại.")
    public MeResponse me(@Parameter(hidden = true) Authentication authentication) {
        return authService.me(currentUser.id(authentication));
    }
}
