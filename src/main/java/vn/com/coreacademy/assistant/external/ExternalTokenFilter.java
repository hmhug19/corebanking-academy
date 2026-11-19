package vn.com.coreacademy.assistant.external;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import vn.com.coreacademy.common.dto.FieldViolation;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.security.UserJwtAuthenticationConverter;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.security.SecurityConstants;

/**
 * Authenticates a Kết nối trợ lý ngoài on {@code /api/v1/mcp}, the only path its token works on: a pasted chan_ext_
 * token (ADR 0004) or an OAuth access token chan_oat_ (ADR 0005). With the feature off
 * the endpoint answers 404 before anything else. A token that is missing, unknown, revoked or expired, of a user who
 * is not active, or of one who no longer holds a role that may draft courses with the assistant, gets 401.
 *
 * <p>The call then runs as that user with their roles as they are now: the filter puts in the context the same kind
 * of authentication a sign-in JWT gives, checked by the same converter (active account, current auth_ver), so every
 * service applies its own role and owner checks unchanged. That in-memory token is never sent anywhere and carries the
 * connection id ({@link #CONNECTION_CLAIM}) and its purpose. The connection's "lần dùng cuối" is written at most once
 * per {@code lastUsedInterval}.
 *
 * <p>Before that, a body over {@code max-body} is refused (413) without being parsed: the body is read here, up to the
 * limit, and handed on as it is. After it, a connection over {@code calls-per-minute} is refused (429, Retry-After).
 * Built by {@link ExternalMcpSecurityConfig}, not a servlet component, so it runs only in
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
    private final ExternalCallLimiter limiter;

    public ExternalTokenFilter(ExternalAssistantProperties properties, ExternalConnectionRepository connections,
                               ExternalConnectionService service, LearningIdentityService identity,
                               UserJwtAuthenticationConverter converter, ObjectMapper mapper,
                               ExternalCallLimiter limiter) {
        this.properties = properties;
        this.connections = connections;
        this.service = service;
        this.identity = identity;
        this.converter = converter;
        this.mapper = mapper;
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        if (!properties.enabled()) {
            write(response, ResponseCode.NOT_FOUND);
            return;
        }
        int maxBody = (int) Math.min(Integer.MAX_VALUE - 1, properties.maxBody().toBytes());
        byte[] body = request.getContentLengthLong() > maxBody ? null
            : request.getInputStream().readNBytes(maxBody + 1);
        if (body == null || body.length > maxBody) {
            // Refused before the MCP server reads a byte of it as JSON.
            log.warn("Refused external assistant call over the body limit maxBytes={}", maxBody);
            write(response, ResponseCode.PAYLOAD_TOO_LARGE, "body", "TOO_LARGE",
                "The request body is over " + maxBody / 1024 + " KB. Send a shorter proposal.");
            return;
        }
        Optional<ConnectionRow> connection = connection(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (connection.isEmpty() || !authenticate(connection.get())) {
            SecurityContextHolder.clearContext();
            // Names the OAuth metadata, so a client that has only the MCP URL can start signing in (RFC 9728 §5.1).
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, SecurityConstants.TOKEN_TYPE + " resource_metadata=\""
                + properties.resourceMetadataUrl() + "\"");
            log.warn("Rejected external assistant call method={} path={} messageCode={}", request.getMethod(),
                request.getRequestURI(), ResponseCode.UNAUTHORIZED.getCode());
            write(response, ResponseCode.UNAUTHORIZED);
            return;
        }
        Duration wait = limiter.admit(connection.get().id());
        if (wait != null) {
            SecurityContextHolder.clearContext();
            long seconds = Math.max(1, wait.toSeconds());
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
            log.warn("Refused external assistant call over the rate limit connectionId={}", connection.get().id());
            write(response, ResponseCode.TOO_MANY_REQUESTS, "connection", "CALLS_PER_MINUTE",
                "At most " + limiter.callsPerMinute() + " calls a minute per connection. Try again in " + seconds
                    + " seconds.");
            return;
        }
        connections.touch(connection.get().id(),
            OffsetDateTime.now().minus(properties.lastUsedInterval()));
        try {
            chain.doFilter(new ReadBody(request, body), response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Optional<ConnectionRow> connection(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER.length()).strip();
        if (token.startsWith(ExternalConnectionCodes.TOKEN_PREFIX)) {
            return connections.findUsable(ExternalConnectionService.hash(token));
        }
        if (token.startsWith(ExternalConnectionCodes.OAUTH_ACCESS_TOKEN_PREFIX)) {
            // An OAuth access token (ADR 0005): an hour old at most, and only while its connection is live.
            return connections.findUsableAccess(ExternalConnectionService.hash(token));
        }

        return Optional.empty();
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
        write(response, BaseResponse.fail(code), code);
    }

    /** A refusal whose one field error tells the Trợ lý ngoài, in English, what to do about it. */
    private void write(HttpServletResponse response, ResponseCode code, String field, String reason, String message)
        throws IOException {
        write(response, BaseResponse.fail(code, List.of(new FieldViolation(field, reason, message))), code);
    }

    private void write(HttpServletResponse response, BaseResponse<Void> body, ResponseCode code) throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), body);
    }

    /** The request with its body already read (within the limit), served again to the MCP server. */
    private static final class ReadBody extends HttpServletRequestWrapper {
        private final byte[] body;

        ReadBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("The body is already read");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return in.read(buffer, offset, length);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8
                : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
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
