package vn.com.coreacademy.assistant.external;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.com.coreacademy.assistant.external.ExternalConnectionRepository.ConnectionRow;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.security.UserJwtAuthenticationConverter;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.security.SecurityConstants;

/**
 * Authenticates a Kết nối trợ lý ngoài on {@code /api/v1/mcp}, the only path its token works on. With the feature off
 * the endpoint answers 404 before anything else. A token that is missing, unknown, revoked or expired, of a user who
 * is not active, or of one who no longer holds a role that may draft courses with the assistant, gets 401.
 *
 * <p>The call then runs as that user with their roles as they are now: the filter puts in the context the same kind
 * of authentication a sign-in JWT gives, checked by the same converter (active account, current auth_ver), so every
 * service applies its own role and owner checks unchanged. That in-memory token is never sent anywhere and carries the
 * connection id ({@link #CONNECTION_CLAIM}) and its purpose. The connection's "lần dùng cuối" is written at most once
 * per {@code lastUsedInterval}. Built by {@link ExternalMcpSecurityConfig}, not a servlet component, so it runs only in
 * that chain.
 */
@Log4j2
public class ExternalTokenFilter extends OncePerRequestFilter {
    /** The claim of the in-memory token that names the connection a call came through. */
    public static final String CONNECTION_CLAIM = "externalConnectionId";
    /** The purpose claim of that token, so nothing mistakes it for a sign-in. */
    public static final String PURPOSE = "external_assistant";
    private static final String BEARER = SecurityConstants.TOKEN_TYPE + " ";

    private final ExternalAssistantProperties properties;
    private final ExternalConnectionRepository connections;
    private final ExternalConnectionService service;
    private final LearningIdentityService identity;
    private final UserJwtAuthenticationConverter converter;
    private final ObjectMapper mapper;

    public ExternalTokenFilter(ExternalAssistantProperties properties, ExternalConnectionRepository connections,
                               ExternalConnectionService service, LearningIdentityService identity,
                               UserJwtAuthenticationConverter converter, ObjectMapper mapper) {
        this.properties = properties;
        this.connections = connections;
        this.service = service;
        this.identity = identity;
        this.converter = converter;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!properties.enabled()) {
            write(response, ResponseCode.NOT_FOUND);
            return;
        }
        Optional<ConnectionRow> connection = connection(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (connection.isEmpty() || !authenticate(connection.get())) {
            SecurityContextHolder.clearContext();
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, SecurityConstants.TOKEN_TYPE);
            log.warn("Rejected external assistant call method={} path={} messageCode={}", request.getMethod(),
                request.getRequestURI(), ResponseCode.UNAUTHORIZED.getCode());
            write(response, ResponseCode.UNAUTHORIZED);
            return;
        }
        connections.touch(connection.get().id(),
            OffsetDateTime.now().minus(properties.lastUsedInterval()));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<ConnectionRow> connection(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER.length()).strip();
        if (!token.startsWith(ExternalConnectionCodes.TOKEN_PREFIX)) {
            return Optional.empty();
        }

        return connections.findUsable(ExternalConnectionService.hash(token));
    }

    /** Puts the connection's user in the context; false (context cleared by the caller) when they may not act now. */
    private boolean authenticate(ConnectionRow connection) {
        long version;
        try {
            version = identity.authorizationVersion(connection.userId());
        } catch (BusinessException exception) {
            return false;
        }
        Instant now = Instant.now();
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
            return false;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            // Roles are read now, at each call: losing the role stops the connection at once.
            return service.mayConnect(connection.userId());
        } catch (RuntimeException exception) {
            // A temporary password (no functions) and similar refusals all mean "may not act now".
            return false;
        }
    }

    private void write(HttpServletResponse response, ResponseCode code) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), BaseResponse.fail(code));
    }

    /** The connection the current call came through; only set inside {@code /api/v1/mcp}. */
    public static long currentConnectionId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
            && PURPOSE.equals(jwt.getClaimAsString(SecurityConstants.TOKEN_PURPOSE_CLAIM))) {
            Object id = jwt.getClaims().get(CONNECTION_CLAIM);
            if (id instanceof Number number) {
                return number.longValue();
            }
        }
        throw new IllegalStateException("Not an external assistant call");
    }
}
