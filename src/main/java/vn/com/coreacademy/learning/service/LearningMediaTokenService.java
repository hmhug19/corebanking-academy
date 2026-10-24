package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.config.LearningMediaProperties;
import vn.com.coreacademy.learning.dto.MediaViews;
import vn.com.coreacademy.security.MediaBearerTokenResolver;
import vn.com.coreacademy.security.SecurityConstants;
import vn.com.coreacademy.security.SecurityContextUtils;

@Service
@RequiredArgsConstructor
public class LearningMediaTokenService {
    private final LearningMediaProperties media;
    private final JwtEncoder encoder;
    private final LearningSessionService sessions;

    public void cookie(MediaViews.Session session, HttpServletRequest request, HttpServletResponse response) {
        renew(session.sessionId(), session.sessionFence(), false, request, response);
    }

    public void renew(UUID sessionId, Long fence, boolean close, HttpServletRequest request,
                      HttpServletResponse response) {
        String value = "";
        long age = 0;
        if (!close) {
            Jwt current = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            Instant now = Instant.now();
            Instant expiry = now.plus(media.getMediaTokenTtl());
            if (current.getExpiresAt() != null && current.getExpiresAt().isBefore(expiry)) {
                expiry = current.getExpiresAt();
            }
            age = Math.max(0, expiry.getEpochSecond() - now.getEpochSecond());
            var claims = JwtClaimsSet.builder().subject(SecurityContextUtils.getUserId())
                .issuer(SecurityConstants.JWT_ISSUER).issuedAt(now).expiresAt(expiry)
                .claim(SecurityConstants.AUTH_VERSION_CLAIM, SecurityContextUtils.getAuthorizationVersion())
                .claim(SecurityConstants.TOKEN_PURPOSE_CLAIM,
                    SecurityConstants.LEARNING_MEDIA_PURPOSE).claim(SecurityConstants.MEDIA_SESSION_CLAIM,
                        sessionId.toString())
                .claim(SecurityConstants.MEDIA_FENCE_CLAIM, fence).build();
            value = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        }
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(MediaBearerTokenResolver.COOKIE, value)
            .httpOnly(true).secure(request.isSecure()).sameSite("Strict")
            .path("/api/v1/learning-assets").maxAge(age).build().toString());
    }

    public void require(Long enrollmentId, Long blockId) {
        var principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!(principal instanceof Jwt token) ||
            !Objects.equals(token.getClaimAsString(SecurityConstants.TOKEN_PURPOSE_CLAIM),
                SecurityConstants.LEARNING_MEDIA_PURPOSE)) {
            throw new AccessDeniedException("Learning media session required");
        }
        Object fence = token.getClaim(SecurityConstants.MEDIA_FENCE_CLAIM);
        if (!(fence instanceof Number number) ||
            token.getClaimAsString(SecurityConstants.MEDIA_SESSION_CLAIM) == null) {
            throw new AccessDeniedException("Learning media session invalid");
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(token.getClaimAsString(SecurityConstants.MEDIA_SESSION_CLAIM));
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("Learning media session invalid");
        }
        try {
            sessions.requireActive(sessions.require(sessionId, number.longValue(), enrollmentId, blockId));
        } catch (BusinessException exception) {
            throw new AccessDeniedException("Learning media session revoked");
        }
    }
}
