package vn.com.coreacademy.identity.security;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.repository.RevokedSessionRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.security.SecurityConstants;

@Component
@RequiredArgsConstructor
public class UserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserRepository users;
    private final RevokedSessionRepository sessions;

    /**
     * Đối chiếu JWT đã qua decoder với tài khoản trong database trên từng yêu cầu. Từ chối tài khoản không
     * active, av không khớp auth_ver hoặc phiên (sid) đã đăng xuất; authority chung chỉ xác nhận đăng nhập, không cấp role/scope nghiệp
     * vụ.
     */
    @Override
    public AbstractAuthenticationToken convert(Jwt token) {
        String id = token.getSubject();
        if (id == null || id.isBlank() || id.length() > 20 || !id.equals(id.trim())) {
            throw new BadCredentialsException("Invalid subject");
        }

        UserAccount user = users.findById(id)
            .orElseThrow(() -> new BadCredentialsException("Unknown user"));

        Object version = token.getClaims().get(SecurityConstants.AUTH_VERSION_CLAIM);
        if (user.getStatus() != UserStatus.ACTIVE || !(version instanceof Number number)
            || number.longValue() != user.getAuthorizationVersion()) {
            throw new BadCredentialsException("Token revoked");
        }
        // A token without sid predates sessions and is still accepted until it expires.
        Object session = token.getClaims().get(SecurityConstants.SESSION_ID_CLAIM);
        if (session != null && (!(session instanceof String sid) || sid.isBlank() || sid.length() > 36
            || sessions.isRevoked(sid))) {
            throw new BadCredentialsException("Session ended");
        }

        return new JwtAuthenticationToken(token,
            List.of(new SimpleGrantedAuthority(SecurityConstants.AUTHENTICATED_AUTHORITY)));
    }
}
