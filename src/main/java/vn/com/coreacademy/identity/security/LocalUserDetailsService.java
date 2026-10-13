package vn.com.coreacademy.identity.security;

import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.identity.constants.SecurityConstants;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.entity.UserCredential;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.repository.UserCredentialRepository;
import vn.com.coreacademy.identity.service.LoginUserResolver;

import java.util.Objects;

@Service
public class LocalUserDetailsService implements UserDetailsService {
    private final LoginUserResolver resolver;
    private final UserCredentialRepository credentials;

    public LocalUserDetailsService(LoginUserResolver resolver, UserCredentialRepository credentials) {
        this.resolver = resolver;
        this.credentials = credentials;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        UserAccount user = resolver.resolve(username);
        UserCredential credential = credentials.findById(user.getId())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        if (!Objects.equals(credential.getUsername(), user.getUsername()))
            throw new UsernameNotFoundException("Invalid credentials");
        // Canonical principal is employee ID, regardless of the login alias used.
        return User.withUsername(user.getId()).password(credential.getPasswordHash())
                .authorities(SecurityConstants.AUTHENTICATED_AUTHORITY)
                .disabled(user.getStatus() != UserStatus.ACTIVE).build();
    }
}
