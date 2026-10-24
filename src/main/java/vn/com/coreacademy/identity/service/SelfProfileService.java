package vn.com.coreacademy.identity.service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.validation.TextValues;
import vn.com.coreacademy.identity.dto.PasswordChangeRequest;
import vn.com.coreacademy.identity.dto.ProfileUpdateRequest;
import vn.com.coreacademy.identity.enums.AuthProvider;
import vn.com.coreacademy.identity.exception.IdentityResponseCode;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.security.PasswordPolicy;
import vn.com.coreacademy.security.SecurityContextUtils;

@Service
@RequiredArgsConstructor
public class SelfProfileService {
    private final UserRepository users;
    private final IdentityAccessPolicy access;
    private final IdentityWriteLock writeLock;
    private final PasswordEncoder passwords;
    private final IdentityAuditService audit;

    /**
     * Sửa các trường hồ sơ được phép của tài khoản LOCAL hiện tại và ghi audit cùng transaction. Chặn tài
     * khoản còn phải đổi mật khẩu tạm; không thay đổi tổ chức, nhóm hoặc quyền.
     */
    @Transactional
    public void update(ProfileUpdateRequest request) {
        updateIfUnchanged(request, Map.of());
    }

    /**
     * Như {@link #update}, nhưng chỉ sửa khi mỗi trường trong {@code expected} (fullName, email, positionName) vẫn
     * mang đúng giá trị đó; chuỗi rỗng coi như không có giá trị. So sánh sau khi giữ khóa ghi, trong cùng transaction,
     * nên không thay đổi nào chen vào giữa lúc so và lúc ghi. Trả false và không ghi gì khi có trường đã khác.
     */
    @Transactional
    public boolean updateIfUnchanged(ProfileUpdateRequest request, Map<String, String> expected) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        var user = access.current(actorId);
        access.requirePasswordChanged(user);
        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            throw new AccessDeniedException("SSO profile is managed by the identity provider");
        }
        if (request.getSuppliedFields().isEmpty()) {
            throw BusinessException.validation("", "EMPTY_UPDATE", "Vui lòng cung cấp thông tin cần cập nhật.");
        }
        for (var entry : expected.entrySet()) {
            String current = switch (entry.getKey()) {
                case "fullName" -> user.getFullName();
                case "email" -> user.getEmail();
                case "positionName" -> user.getPositionName();
                default -> throw new IllegalArgumentException("Not a profile field: " + entry.getKey());
            };
            if (!Objects.equals(blankToNull(current), blankToNull(entry.getValue()))) {
                return false;
            }
        }

        if (request.has("fullName")) {
            user.setFullName(TextValues.required(request.getFullName(), "fullName"));
        }
        if (request.has("email")) {
            user.setEmail(request.getEmail());
        }
        if (request.has("positionName")) {
            user.setPositionName(request.getPositionName());
        }

        users.save(user);
        audit.record(actorId, "USER_PROFILE_UPDATED", "USER", actorId, user.getOrgId(),
            Map.of("fields", request.getSuppliedFields()));
        return true;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Đổi mật khẩu LOCAL sau khi đối chiếu mật khẩu hiện tại và kiểm tra chính sách mật khẩu mới. Hoàn tất yêu
     * cầu đổi mật khẩu tạm, thu hồi mọi token cũ và ghi audit cùng transaction.
     */
    @Transactional
    public void password(PasswordChangeRequest request) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        var user = access.current(actorId);
        if (user.getAuthProvider() != AuthProvider.LOCAL || user.getPasswordHash() == null) {
            throw new AccessDeniedException("Password is managed by the identity provider");
        }

        PasswordPolicy.validate(request.newPassword(), "newPassword");
        if (!passwords.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(IdentityResponseCode.CURRENT_PASSWORD_INCORRECT);
        }
        if (passwords.matches(request.newPassword(), user.getPasswordHash())) {
            throw BusinessException.validation("newPassword", "SAME_AS_CURRENT",
                "Mật khẩu mới phải khác mật khẩu hiện tại.");
        }

        user.setPasswordHash(passwords.encode(request.newPassword()));
        user.setPasswordChangedTime(Instant.now());
        user.setMustChangePassword(false);
        user.setAuthorizationVersion(user.getAuthorizationVersion() + 1);

        users.save(user);
        audit.record(actorId, "USER_PASSWORD_CHANGED", "USER", actorId, user.getOrgId(), Map.of());
    }
}
