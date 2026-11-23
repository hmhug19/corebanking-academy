package vn.com.coreacademy.assistant.external;

import java.time.Instant;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.external.ExternalConnectionRepository.ConnectionRow;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.security.UserJwtAuthenticationConverter;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.security.SecurityConstants;

/**
 * Whether a connection's user may act now: an active account with a current auth version (the same checks a sign-in
 * JWT gets) that still holds a role that may draft courses with the assistant. Read at every MCP call and at every
 * OAuth refresh, so losing the role stops a connection at once and never lets it be renewed.
 */
@Component
@RequiredArgsConstructor
public class ExternalConnectionAuthenticator {
    /** The claim of the in-memory token that names the connection a call came through. */
    public static final String CONNECTION_CLAIM = "externalConnectionId";
    /** The purpose claim of that token, so nothing mistakes it for a sign-in. */
    public static final String PURPOSE = "external_assistant";

    private final ExternalConnectionService service;
    private final LearningIdentityService identity;
    private final UserJwtAuthenticationConverter converter;

    /**
     * The user's authentication when they may act now, empty otherwise. The role check reads the security context,
     * so it runs with this authentication in place; the caller's own context is restored afterwards.
     */
    public Optional<Authentication> authenticate(ConnectionRow connection) {
        Instant now = Instant.now();
        if (!connection.expiresAt().toInstant().isAfter(now)) {
            return Optional.empty();
        }
        long version;
        try {
            version = identity.authorizationVersion(connection.userId());
        } catch (BusinessException exception) {
            return Optional.empty();
        }
        Jwt token = Jwt.withTokenValue("external-connection-" + connection.id())
            .header("alg", "none")
            .subject(connection.userId())
            .claim(SecurityConstants.AUTH_VERSION_CLAIM, version)
            .claim(SecurityConstants.TOKEN_PURPOSE_CLAIM, PURPOSE)
            .claim(CONNECTION_CLAIM, connection.id())
            .issuedAt(now)
            .expiresAt(connection.expiresAt().toInstant())
            .build();
        Authentication authentication;
        try {
            authentication = converter.convert(token);
        } catch (AuthenticationException exception) {
            return Optional.empty();
        }
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            return service.mayConnect(connection.userId()) ? Optional.of(authentication) : Optional.empty();
        } catch (RuntimeException exception) {
            // A temporary password (no functions) and similar refusals all mean "may not act now".
            return Optional.empty();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
