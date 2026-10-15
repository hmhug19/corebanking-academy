package vn.com.coreacademy.identity.service;

import lombok.RequiredArgsConstructor;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.identity.config.JwtProperties;
import vn.com.coreacademy.identity.constants.SecurityConstants;
import vn.com.coreacademy.identity.dto.TokenResponse;
import vn.com.coreacademy.identity.entity.UserAccount;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class TokenService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public TokenResponse issue(UserAccount user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.expiryMinutes(), ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(SecurityConstants.JWT_ISSUER)
                .subject(user.getId())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(SecurityConstants.AUTH_VERSION_CLAIM, user.getAuthorizationVersion())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new TokenResponse(accessToken, SecurityConstants.TOKEN_TYPE,
                ChronoUnit.SECONDS.between(now, expiresAt));
    }
}
