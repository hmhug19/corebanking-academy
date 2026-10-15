package vn.com.coreacademy.identity.security;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.identity.constants.SecurityConstants;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.AuthProvider;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.service.LoginUserResolver;

@Service
@RequiredArgsConstructor
public class LocalUserDetailsService implements UserDetailsService {
    private final LoginUserResolver resolver;

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserAccount user = resolver.resolve(username);
        if (user.getAuthProvider() != AuthProvider.LOCAL || user.getPasswordHash() == null || user.getPasswordHash().isBlank())
            throw new UsernameNotFoundException("Invalid credentials");
        return User.withUsername(user.getId()).password(user.getPasswordHash())
                .authorities(SecurityConstants.AUTHENTICATED_AUTHORITY)
                .disabled(user.getStatus() == UserStatus.INACTIVE)
                .accountLocked(user.getStatus() == UserStatus.LOCKED).build();
    }
}
