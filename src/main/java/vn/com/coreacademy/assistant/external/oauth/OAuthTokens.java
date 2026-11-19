package vn.com.coreacademy.assistant.external.oauth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assistant.external.ExternalConnectionRepository;
import vn.com.coreacademy.assistant.external.ExternalConnectionRepository.ConnectionRow;
import vn.com.coreacademy.assistant.external.ExternalConnectionService;
import vn.com.coreacademy.assistant.external.oauth.OAuthGrantRepository.CodeRow;

/**
 * The token endpoint and revocation (RFC 6749, RFC 7636, RFC 7009). Access tokens live an hour; a refresh rotates
 * the pair and pushes the connection's expiry 30 days out, so a connection lapses after 30 days unused. A code or a
 * rotated refresh token used a second time means it leaked: the connection is revoked. Tokens are random strings
 * stored only as SHA-256, like the chan_ext_ ones.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class OAuthTokens {
    private final OAuthServer server;
    private final OAuthClientRepository clients;
    private final OAuthGrantRepository grants;
    private final ExternalConnectionRepository connections;
    private final OAuthAudit audit;

    /** The token response for a {@code grant_type} of authorization_code or refresh_token. */
    @Transactional(noRollbackFor = OAuthException.class)
    public Map<String, Object> token(Map<String, String> form) {
        server.requireEnabled();
        String grantType = form.get("grant_type");
        if ("authorization_code".equals(grantType)) {
            return exchange(form);
        }
        if ("refresh_token".equals(grantType)) {
            return refresh(form);
        }
        throw OAuthException.badRequest("unsupported_grant_type",
            "grant_type must be authorization_code or refresh_token.");
    }

    /** RFC 7009: revokes the connection of an access or refresh token; always answers the same. */
    @Transactional
    public void revoke(Map<String, String> form) {
        server.requireEnabled();
        String token = form.get("token");
        if (token == null || token.isBlank()) {
            throw OAuthException.badRequest("invalid_request", "token is required.");
        }
        connections.findByOAuthToken(ExternalConnectionService.hash(token.strip()))
            .filter(connection -> form.get("client_id") == null
                || form.get("client_id").equals(connection.clientId()))
            .ifPresent(connection -> {
                if (connections.revokeAny(connection.id())) {
                    audit.record(OAuthAudit.Event.REVOKED, connection.userId(), connection.id(),
                        connection.clientId(), "rfc7009");
                }
            });
    }

    private Map<String, Object> exchange(Map<String, String> form) {
        String code = required(form, "code");
        String clientId = client(form);
        String verifier = required(form, "code_verifier");
        String redirect = required(form, "redirect_uri");

        String codeHash = ExternalConnectionService.hash(code);
        Optional<CodeRow> claimed = grants.claimCode(codeHash);
        if (claimed.isEmpty()) {
            // A code used twice leaked: whatever it gave stops (RFC 6749 §4.1.2).
            grants.usedCodeConnection(codeHash).ifPresent(id -> connections.find(id).ifPresent(connection -> {
                if (connections.revokeAny(id)) {
                    audit.record(OAuthAudit.Event.REVOKED, connection.userId(), id, connection.clientId(),
                        "code_reused");
                }
            }));
            throw invalidGrant("The code is unknown, expired or already used.");
        }
        CodeRow row = claimed.get();
        ConnectionRow connection = connections.find(row.connectionId())
            .orElseThrow(() -> invalidGrant("The code is unknown, expired or already used."));
        if (!clientId.equals(connection.clientId()) || !redirect.equals(row.redirectUri())) {
            throw invalidGrant("client_id or redirect_uri does not match the authorization request.");
        }
        if (!pkce(verifier, row.codeChallenge())) {
            throw invalidGrant("code_verifier does not match the code_challenge.");
        }
        String access = OAuthServer.random(OAuthCodes.ACCESS_TOKEN_PREFIX, 32);
        String refresh = OAuthServer.random(OAuthCodes.REFRESH_TOKEN_PREFIX, 32);
        OffsetDateTime now = OffsetDateTime.now();
        if (!connections.issueTokens(connection.id(), ExternalConnectionService.hash(access),
            now.plus(OAuthCodes.ACCESS_TOKEN_TTL), ExternalConnectionService.hash(refresh),
            now.plus(OAuthCodes.IDLE_EXPIRY))) {
            throw invalidGrant("The connection was revoked or has expired.");
        }
        return response(access, refresh);
    }

    private Map<String, Object> refresh(Map<String, String> form) {
        String token = required(form, "refresh_token");
        String clientId = client(form);
        String scope = form.get("scope");
        if (scope != null && !scope.isBlank() && !OAuthCodes.SCOPE.equals(scope.strip())) {
            throw OAuthException.badRequest("invalid_scope", "The only scope is " + OAuthCodes.SCOPE + ".");
        }
        String hash = ExternalConnectionService.hash(token);
        Optional<ConnectionRow> known = connections.findByOAuthToken(hash);
        if (known.isPresent() && !clientId.equals(known.get().clientId())) {
            throw invalidGrant("The refresh token was not issued to this client.");
        }
        Optional<Map<String, Object>> rotated = rotate(hash);
        if (rotated.isPresent()) {
            return rotated.get();
        }
        if (known.isPresent() && connections.isPreviousRefresh(known.get().id(), hash)) {
            ConnectionRow connection = known.get();
            if (connections.revokeAny(connection.id())) {
                log.warn("Revoked an external assistant connection whose refresh token was reused connectionId={}",
                    connection.id());
                audit.record(OAuthAudit.Event.REFRESH_REUSED, connection.userId(), connection.id(),
                    connection.clientId(), null);
            }
        } else {
            ConnectionRow connection = known.orElse(null);
            audit.record(OAuthAudit.Event.REFRESH_REFUSED, connection == null ? null : connection.userId(),
                connection == null ? null : connection.id(), clientId,
                connection == null ? "unknown" : "not_live");
        }
        throw invalidGrant("The refresh token is unknown, expired, revoked or already used.");
    }

    /** The new pair if this is the current refresh token of a live connection; empty otherwise. */
    private Optional<Map<String, Object>> rotate(String refreshHash) {
        String access = OAuthServer.random(OAuthCodes.ACCESS_TOKEN_PREFIX, 32);
        String refresh = OAuthServer.random(OAuthCodes.REFRESH_TOKEN_PREFIX, 32);
        OffsetDateTime now = OffsetDateTime.now();
        return connections.rotate(refreshHash, ExternalConnectionService.hash(access),
                now.plus(OAuthCodes.ACCESS_TOKEN_TTL), ExternalConnectionService.hash(refresh),
                now.plus(OAuthCodes.IDLE_EXPIRY))
            .map(connection -> response(access, refresh));
    }

    private static Map<String, Object> response(String access, String refresh) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", access);
        body.put("token_type", "Bearer");
        body.put("expires_in", OAuthCodes.ACCESS_TOKEN_TTL.toSeconds());
        body.put("refresh_token", refresh);
        body.put("scope", OAuthCodes.SCOPE);
        return body;
    }

    /** The client_id of a public client; an unknown one is refused (RFC 6749 §5.2). */
    private String client(Map<String, String> form) {
        String clientId = required(form, "client_id");
        if (clients.find(clientId).isEmpty()) {
            throw new OAuthException(HttpStatus.UNAUTHORIZED, "invalid_client", "Unknown client_id.");
        }
        return clientId;
    }

    /** RFC 7636 S256: base64url(SHA-256(verifier)) equals the challenge, and the verifier has the allowed shape. */
    static boolean pkce(String verifier, String challenge) {
        if (verifier.length() < 43 || verifier.length() > 128 || !verifier.matches("[A-Za-z0-9._~-]+")) {
            return false;
        }
        try {
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            return MessageDigest.isEqual(computed.getBytes(StandardCharsets.US_ASCII),
                challenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String required(Map<String, String> form, String name) {
        String value = form.get(name);
        if (value == null || value.isBlank()) {
            throw OAuthException.badRequest("invalid_request", name + " is required.");
        }
        return value.strip();
    }

    private static OAuthException invalidGrant(String description) {
        return OAuthException.badRequest("invalid_grant", description);
    }
}
