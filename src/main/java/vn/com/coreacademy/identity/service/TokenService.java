package vn.com.coreacademy.identity.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.config.JwtProperties;
import vn.com.coreacademy.identity.dto.TokenResponse;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.security.SecurityConstants;

@Service
@RequiredArgsConstructor
public class TokenService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final RoleAssignmentRepository assignments;

    /**
     * Phát JWT HS256 với subject là mã cán bộ dạng chuỗi, av là phiên bản quyền hiện tại và sid là mã phiên mới
     * (đăng xuất chỉ thu hồi phiên này). Claim roles chỉ là
     * ảnh chụp mã vai trò lúc phát; backend không dùng claim này làm nguồn cấp quyền hoặc scope.
     */
    public TokenResponse issue(UserAccount user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.expiryMinutes(), ChronoUnit.MINUTES);

        // Claim này chỉ hỗ trợ hiển thị; quyền hành động vẫn lấy từ phân quyền trong database.
        var roles = assignments.findActiveForUser(user.getId()).stream()
            .map(grant -> grant.getRole().getCode())
            .distinct().sorted().toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(SecurityConstants.JWT_ISSUER)
            .subject(user.getId())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(SecurityConstants.AUTH_VERSION_CLAIM, user.getAuthorizationVersion())
            .claim(SecurityConstants.SESSION_ID_CLAIM, UUID.randomUUID().toString())
            .claim("roles", roles)
            .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(accessToken, SecurityConstants.TOKEN_TYPE,
            ChronoUnit.SECONDS.between(now, expiresAt));
    }
}
