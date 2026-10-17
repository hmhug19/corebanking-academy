package vn.com.coreacademy.identity.service;

import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.dto.FunctionInfo;
import vn.com.coreacademy.identity.dto.LoginRequest;
import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.dto.TokenResponse;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.exception.IdentityResponseCode;
import vn.com.coreacademy.identity.mapper.UserMapper;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.organization.repository.OrganizationRepository;
import vn.com.coreacademy.organization.repository.TeamRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final AuthenticationManager authenticationManager;
    private final UserRepository users;
    private final AuthorizationService authorization;
    private final TokenService tokens;
    private final UserMapper userMapper;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    /**
     * Xác thực tên đăng nhập hoặc mã cán bộ qua AuthenticationManager rồi phát token cho mã cán bộ chuẩn.
     * Không dùng định danh nhập vào làm JWT subject khi chưa xác thực.
     */
    public TokenResponse login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (BadCredentialsException | AccountStatusException exception) {
            // Cùng thông báo cho sai mật khẩu, user không tồn tại hoặc bị chặn để tránh dò tài khoản.
            throw new BusinessException(IdentityResponseCode.INVALID_CREDENTIALS);
        }

        UserAccount user = users.findById(authentication.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        return tokens.issue(user);
    }

    /**
     * Thu hồi mọi phiên của tài khoản hiện tại và ghi audit trong cùng transaction.
     *
     * <p>So sánh auth_ver của token với database khi cập nhật để một yêu cầu dùng token cũ không thu hồi nhầm
     * phiên mới; token đã bị thu hồi trả lỗi xác thực.
     */
    @Transactional
    public void logout() {
        String userId = SecurityContextUtils.getUserId();
        long tokenVersion = SecurityContextUtils.getAuthorizationVersion();
        writeLock.acquire();

        if (users.revokeTokens(userId, tokenVersion) != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token already revoked");
        }

        UserAccount user = users.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        audit.record(userId, "USER_LOGOUT", "USER", userId, user.getOrgId(), Map.of("allSessions", true));
    }

    /**
     * Tổng hợp hồ sơ và quyền hiện tại từ database cho tài khoản đã xác thực. Quyền trả về phục vụ hiển thị;
     * policy vẫn kiểm tra từng thao tác và từng tài nguyên.
     */
    @Transactional(readOnly = true)
    public MeResponse me() {
        String userId = SecurityContextUtils.getUserId();

        UserAccount user = users.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));

        var organization = user.getOrgId() == null ? null : organizations.findById(user.getOrgId()).orElse(null);
        var team = user.getTeamId() == null ? null : teams.findById(user.getTeamId()).orElse(null);
        // Không hiển thị nhóm khác tổ chức khi dữ liệu hồ sơ bị lệch.
        if (team != null && !Objects.equals(team.getOrgId(), user.getOrgId())) {
            team = null;
        }

        var rights = authorization.rights(userId);
        return new MeResponse(userMapper.toInfo(user, organization, team),
            rights.roles().stream().map(RoleGrant::roleCode).distinct().sorted().toList(),
            rights.functions().stream().map(FunctionInfo::code).distinct().toList(),
            rights.roles(), rights.functions(), rights.functionScopes());
    }
}
