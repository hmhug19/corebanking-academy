package vn.com.coreacademy.assistant.external.oauth;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The steps between a client's authorize and its tokens: the request waiting on the Cho phép kết nối page, and the
 * one-time code an approval gives. Each is used once; a code is stored only as its SHA-256.
 */
@Repository
@RequiredArgsConstructor
public class OAuthGrantRepository {
    public record RequestRow(String requestId, String clientId, String redirectUri, String state, String codeChallenge,
                             String scope, OffsetDateTime expiresAt, OffsetDateTime usedAt) {
    }

    public record CodeRow(long connectionId, String redirectUri, String codeChallenge) {
    }

    private static final RowMapper<RequestRow> REQUEST = (rs, row) -> new RequestRow(rs.getString("request_id"),
        rs.getString("client_id"), rs.getString("redirect_uri"), rs.getString("state"),
        rs.getString("code_challenge"), rs.getString("scope"), rs.getObject("expires_at", OffsetDateTime.class),
        rs.getObject("used_at", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    public void insertRequest(String requestId, String clientId, String redirectUri, String state,
                              String codeChallenge, String scope, OffsetDateTime expiresAt) {
        jdbc.update("""
            INSERT INTO learning_hub.oauth_authorization_request (request_id, client_id, redirect_uri, state,
                                                                  code_challenge, scope, expires_at)
            VALUES (:id, :client, :redirect, :state, :challenge, :scope, :expires)
            """, new MapSqlParameterSource().addValue("id", requestId).addValue("client", clientId)
            .addValue("redirect", redirectUri).addValue("state", state).addValue("challenge", codeChallenge)
            .addValue("scope", scope).addValue("expires", expiresAt));
    }

    /** The request if it is neither used nor expired. */
    public Optional<RequestRow> findOpenRequest(String requestId) {
        return jdbc.query("""
            SELECT request_id, client_id, redirect_uri, state, code_challenge, scope, expires_at, used_at
              FROM learning_hub.oauth_authorization_request
             WHERE request_id = :id
               AND used_at IS NULL
               AND expires_at > now()
            """, Map.of("id", requestId), REQUEST).stream().findFirst();
    }

    /** Marks the open request used and returns it; empty when it was used, expired or never existed. */
    public Optional<RequestRow> claimRequest(String requestId) {
        return jdbc.query("""
            UPDATE learning_hub.oauth_authorization_request
               SET used_at = now()
             WHERE request_id = :id
               AND used_at IS NULL
               AND expires_at > now()
            RETURNING request_id, client_id, redirect_uri, state, code_challenge, scope, expires_at, used_at
            """, Map.of("id", requestId), REQUEST).stream().findFirst();
    }

    public void insertCode(String codeHash, long connectionId, String redirectUri, String codeChallenge,
                           OffsetDateTime expiresAt) {
        jdbc.update("""
            INSERT INTO learning_hub.oauth_authorization_code (code_hash, connection_id, redirect_uri,
                                                               code_challenge, expires_at)
            VALUES (:hash, :connection, :redirect, :challenge, :expires)
            """, new MapSqlParameterSource().addValue("hash", codeHash).addValue("connection", connectionId)
            .addValue("redirect", redirectUri).addValue("challenge", codeChallenge).addValue("expires", expiresAt));
    }

    /** Marks the code used and returns it; empty when it was used, expired or never existed. */
    public Optional<CodeRow> claimCode(String codeHash) {
        return jdbc.query("""
            UPDATE learning_hub.oauth_authorization_code
               SET used_at = now()
             WHERE code_hash = :hash
               AND used_at IS NULL
               AND expires_at > now()
            RETURNING connection_id, redirect_uri, code_challenge
            """, Map.of("hash", codeHash), (rs, row) -> new CodeRow(rs.getLong("connection_id"),
            rs.getString("redirect_uri"), rs.getString("code_challenge"))).stream().findFirst();
    }

    /** Whether the code was issued and already used: a second use means it leaked. */
    public Optional<Long> usedCodeConnection(String codeHash) {
        return jdbc.query("""
            SELECT connection_id
              FROM learning_hub.oauth_authorization_code
             WHERE code_hash = :hash
               AND used_at IS NOT NULL
            """, Map.of("hash", codeHash), (rs, row) -> rs.getLong("connection_id")).stream().findFirst();
    }
}
