package vn.com.coreacademy.identity.security;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.AuthProvider;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.service.LoginUserResolver;
import vn.com.coreacademy.security.SecurityConstants;

@Service
@RequiredArgsConstructor
public class LocalUserDetailsService implements UserDetailsService {
    private final LoginUserResolver resolver;

    /**
     * Nạp tài khoản LOCAL cho bộ xác thực mật khẩu và dùng mã cán bộ làm tên principal chuẩn. Tài khoản SSO
     * không được xác thực bằng password local; trạng thái inactive/locked được chuyển sang cờ của Spring
     * Security.
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        UserAccount user = resolver.resolve(username);
        if (user.getAuthProvider() != AuthProvider.LOCAL || user.getPasswordHash() == null
            || user.getPasswordHash().isBlank()) {
            throw new UsernameNotFoundException("Invalid credentials");
        }

        return User.withUsername(user.getId()).password(user.getPasswordHash())
            .authorities(SecurityConstants.AUTHENTICATED_AUTHORITY)
            .disabled(user.getStatus() == UserStatus.INACTIVE)
            .accountLocked(user.getStatus() == UserStatus.LOCKED).build();
    }
}
