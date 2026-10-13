package vn.com.coreacademy.identity.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public String id(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new BadCredentialsException("Invalid authentication");
        }
        String id = jwt.getSubject();
        if (id == null || id.isBlank() || id.length() > 20 || !id.equals(id.trim()))
            throw new BadCredentialsException("Invalid subject");
        return id;
    }
}
