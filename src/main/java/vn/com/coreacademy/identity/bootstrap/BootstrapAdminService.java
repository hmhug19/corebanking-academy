package vn.com.coreacademy.identity.bootstrap;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.identity.constants.SecurityConstants;
import vn.com.coreacademy.identity.entity.Role;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.entity.UserCredential;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.identity.repository.RoleRepository;
import vn.com.coreacademy.identity.repository.UserCredentialRepository;
import vn.com.coreacademy.identity.repository.UserRepository;

import java.util.Locale;

@Service
public class BootstrapAdminService {
    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final RoleRepository roles;
    private final RoleAssignmentRepository grants;
    private final PasswordEncoder encoder;

    public BootstrapAdminService(UserRepository users, UserCredentialRepository credentials,
                                 RoleRepository roles, RoleAssignmentRepository grants, PasswordEncoder encoder) {
        this.users = users;
        this.credentials = credentials;
        this.roles = roles;
        this.grants = grants;
        this.encoder = encoder;
    }

    @Transactional
    public void create(String userId, String username, String password) {
        if (userId.isBlank() && username.isBlank() && password.isBlank()) return;
        if (userId.isBlank() || username.isBlank() || password.isBlank())
            throw new IllegalArgumentException("Bootstrap requires ADMIN_ID, ADMIN_USER and ADMIN_PASSWORD");
        if (userId.length() > 20 || !userId.equals(userId.trim()) || username.trim().length() > 100)
            throw new IllegalArgumentException("Invalid bootstrap employee ID or username");
        if (password.length() < 12)
            throw new IllegalArgumentException("Bootstrap admin password must have at least 12 characters");
        String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
        if (users.findById(userId).isPresent()) {
            if (!users.findById(userId).orElseThrow().getUsername().equals(normalizedUsername))
                throw new IllegalArgumentException("Bootstrap ID already belongs to a different username");
            return; // Never reset an existing user's credentials or privileges at startup.
        }
        if (!users.findLoginCandidates(userId, normalizedUsername).isEmpty()
                || !users.findLoginCandidates(normalizedUsername, userId.toLowerCase(Locale.ROOT)).isEmpty())
            throw new IllegalArgumentException("Bootstrap identifiers conflict with an existing account");
        Role role = roles.findByCode(SecurityConstants.ADMIN_ROLE)
                .filter(Role::isActive).orElseThrow(() -> new IllegalStateException("Run the identity SQL seed first"));
        UserAccount user = new UserAccount();
        user.setId(userId);
        user.setUsername(normalizedUsername);
        user.setFullName("Bootstrap Admin");
        users.saveAndFlush(user);
        UserCredential credential = new UserCredential();
        credential.setUserId(userId);
        credential.setUsername(normalizedUsername);
        credential.setPasswordHash(encoder.encode(password));
        credentials.save(credential);
        RoleAssignment assignment = new RoleAssignment();
        assignment.setUser(user);
        assignment.setRole(role);
        assignment.setScopeType(ScopeType.GLOBAL);
        grants.save(assignment);
    }
}
