package vn.com.coreacademy.identity.bootstrap;

import java.util.Locale;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.entity.Role;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.identity.repository.RoleRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.security.PasswordPolicy;
import vn.com.coreacademy.identity.service.IdentityWriteLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class BootstrapAdminService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final RoleAssignmentRepository grants;
    private final PasswordEncoder encoder;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    /**
     * Khởi tạo SUPER_ADMIN GLOBAL đầu tiên khi cung cấp đủ cấu hình và chưa có tài khoản gốc hoạt động.
     * Tài khoản mới phải đổi mật khẩu tạm; user, grant và audit cùng transaction có khóa ghi chung.
     * Không reset mật khẩu hoặc nâng quyền tài khoản đã tồn tại khi restart.
     */
    @Transactional
    public void create(String userId, String username, String password) {
        if (userId.isBlank() && username.isBlank() && password.isBlank()) {
            return;
        }
        if (userId.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalArgumentException("Bootstrap requires ADMIN_ID, ADMIN_USER and ADMIN_PASSWORD");
        }
        if (!userId.matches("[A-Za-z0-9._-]{1,20}") || !username.trim().matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("Invalid bootstrap employee ID or username");
        }

        PasswordPolicy.validate(password, "password");

        writeLock.acquire();
        String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
        if (users.findById(userId).isPresent()) {
            if (!users.findById(userId).orElseThrow().getUsername().equals(normalizedUsername)) {
                throw new IllegalArgumentException("Bootstrap ID already belongs to a different username");
            }
            log.info("Bootstrap account already exists; credentials and grants remain unchanged");
            return;
        }

        if (!grants.findEffectiveSuperAdministrators().isEmpty()) {
            log.info("Active SUPER_ADMIN already exists; bootstrap skipped");
            return;
        }

        if (!users.findLoginCandidates(userId, normalizedUsername).isEmpty()
            || !users.findLoginCandidates(normalizedUsername, userId.toLowerCase(Locale.ROOT)).isEmpty()) {
            throw new IllegalArgumentException("Bootstrap identifiers conflict with an existing account");
        }

        Role role = roles.findByCode(RoleCodes.SUPER_ADMIN)
            .filter(Role::isActive)
            .orElseThrow(() -> new IllegalStateException("Run the SUPER_ADMIN catalog SQL upgrade first"));

        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setUsername(normalizedUsername);
        user.setFullName("Bootstrap Super Admin");
        user.setPasswordHash(encoder.encode(password));
        user.setMustChangePassword(true);
        users.saveAndFlush(user);

        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setScopeType(ScopeType.GLOBAL);
        assignment.setGrantedBy(userId);
        grants.save(assignment);

        audit.record(userId, "SUPER_ADMIN_BOOTSTRAPPED", "USER", userId, null,
            Map.of("roleCode", RoleCodes.SUPER_ADMIN, "scope", ScopeType.GLOBAL.name(),
                "grantId", assignment.getId()));
    }
}
