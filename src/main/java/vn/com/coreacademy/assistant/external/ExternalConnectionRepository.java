package vn.com.coreacademy.assistant.external;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The Kết nối trợ lý ngoài of each user. Only a token's hash and display prefix are ever written. */
@Repository
@RequiredArgsConstructor
public class ExternalConnectionRepository {
    /** {@code kind} is {@link #TOKEN} or {@link #OAUTH}; an oauth row has a client and no token prefix. */
    public record ConnectionRow(long id, String userId, String name, String tokenPrefix, OffsetDateTime createTime,
                                OffsetDateTime expiresAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt,
                                String kind, String clientId) {
    }

    /** A pasted chan_ext_ token (ADR 0004). */
    public static final String TOKEN = "token";
    /** Granted on the Cho phép kết nối page (ADR 0005). */
    public static final String OAUTH = "oauth";

    private static final String COLUMNS = """
        connection_id, user_id, name, token_prefix, create_time, expires_at, last_used_at, revoked_at, kind, client_id
        """;

    private static final RowMapper<ConnectionRow> CONNECTION = (rs, row) -> new ConnectionRow(
        rs.getLong("connection_id"), rs.getString("user_id"), rs.getString("name"), rs.getString("token_prefix"),
        rs.getObject("create_time", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
        rs.getObject("last_used_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class),
        rs.getString("kind"), rs.getString("client_id"));

    private final NamedParameterJdbcTemplate jdbc;

    public long insert(String userId, String name, String tokenHash, String tokenPrefix, OffsetDateTime expiresAt) {
        return jdbc.queryForObject("""
            INSERT INTO learning_hub.external_assistant_connection (user_id, name, token_hash, token_prefix,
                                                                    expires_at)
            VALUES (:user, :name, :hash, :prefix, :expires)
            RETURNING connection_id
            """, new MapSqlParameterSource().addValue("user", userId).addValue("name", name)
            .addValue("hash", tokenHash).addValue("prefix", tokenPrefix).addValue("expires", expiresAt), Long.class);
    }

    public Optional<ConnectionRow> find(long connectionId) {
        return jdbc.query("""
            SELECT %s
              FROM learning_hub.external_assistant_connection
             WHERE connection_id = :id
            """.formatted(COLUMNS), Map.of("id", connectionId), CONNECTION).stream().findFirst();
    }

    /** The user's connections that are not revoked, newest first; expired ones stay listed until revoked. */
    public List<ConnectionRow> listOwn(String userId) {
        return jdbc.query("""
            SELECT %s
              FROM learning_hub.external_assistant_connection
             WHERE user_id = :user
               AND revoked_at IS NULL
               -- An approval whose code was never exchanged leaves nothing to show.
               AND NOT (kind = 'oauth' AND access_token_hash IS NULL AND expires_at <= now())
             ORDER BY create_time DESC, connection_id DESC
            """.formatted(COLUMNS), Map.of("user", userId), CONNECTION);
    }

    /** Revokes the user's own connection; false when it is not theirs or already revoked. */
    public boolean revoke(long connectionId, String userId) {
        return jdbc.update("""
            UPDATE learning_hub.external_assistant_connection
               SET revoked_at = now()
             WHERE connection_id = :id
               AND user_id = :user
               AND revoked_at IS NULL
            """, new MapSqlParameterSource().addValue("id", connectionId).addValue("user", userId)) == 1;
    }

    /** The connection a token's hash names, if it is neither revoked nor expired. */
    public Optional<ConnectionRow> findUsable(String tokenHash) {
        return jdbc.query("""
            SELECT %s
              FROM learning_hub.external_assistant_connection
             WHERE token_hash = :hash
               AND revoked_at IS NULL
               AND expires_at > now()
            """.formatted(COLUMNS), Map.of("hash", tokenHash), CONNECTION).stream().findFirst();
    }

    /**
     * A connection granted on the Cho phép kết nối page. It lives only until {@code expiresAt}, the code's lifetime,
     * unless its code is exchanged for tokens.
     */
    public long insertOAuth(String userId, String name, String clientId, OffsetDateTime expiresAt) {
        return jdbc.queryForObject("""
            INSERT INTO learning_hub.external_assistant_connection (user_id, name, kind, client_id, expires_at)
            VALUES (:user, :name, 'oauth', :client, :expires)
            RETURNING connection_id
            """, new MapSqlParameterSource().addValue("user", userId).addValue("name", name)
            .addValue("client", clientId).addValue("expires", expiresAt), Long.class);
    }

    /** The user's connections that work now or may: not revoked and not expired, of both kinds. */
    public int countLive(String userId) {
        return jdbc.queryForObject("""
            SELECT count(*)
              FROM learning_hub.external_assistant_connection
             WHERE user_id = :user
               AND revoked_at IS NULL
               AND expires_at > now()
            """, Map.of("user", userId), Integer.class);
    }

    /**
     * Gives a live oauth connection a new token pair and pushes its expiry to {@code expiresAt}; the refresh token it
     * had becomes the previous one, so using it again is recognised. False when the connection is not live.
     */
    public boolean issueTokens(long connectionId, String accessHash, OffsetDateTime accessExpiresAt,
                               String refreshHash, OffsetDateTime expiresAt) {
        return jdbc.update("""
            UPDATE learning_hub.external_assistant_connection
               SET access_token_hash = :access, access_expires_at = :accessExpires,
                   previous_refresh_hash = refresh_token_hash, refresh_token_hash = :refresh, expires_at = :expires
             WHERE connection_id = :id
               AND kind = 'oauth'
               AND revoked_at IS NULL
               AND expires_at > now()
            """, new MapSqlParameterSource().addValue("id", connectionId).addValue("access", accessHash)
            .addValue("accessExpires", accessExpiresAt).addValue("refresh", refreshHash)
            .addValue("expires", expiresAt)) == 1;
    }

    /**
     * Rotates the pair of the live connection whose current refresh token has this hash, in one statement so two
     * refreshes with the same token cannot both win. The connection, or empty.
     */
    public Optional<ConnectionRow> rotate(String refreshHash, String accessHash, OffsetDateTime accessExpiresAt,
                                          String newRefreshHash, OffsetDateTime expiresAt) {
        return jdbc.query("""
            UPDATE learning_hub.external_assistant_connection
               SET access_token_hash = :access, access_expires_at = :accessExpires,
                   previous_refresh_hash = refresh_token_hash, refresh_token_hash = :refresh, expires_at = :expires
             WHERE refresh_token_hash = :old
               AND kind = 'oauth'
               AND revoked_at IS NULL
               AND expires_at > now()
            RETURNING %s
            """.formatted(COLUMNS), new MapSqlParameterSource().addValue("old", refreshHash)
            .addValue("access", accessHash).addValue("accessExpires", accessExpiresAt)
            .addValue("refresh", newRefreshHash).addValue("expires", expiresAt), CONNECTION).stream().findFirst();
    }

    /** The oauth connection, in any state, whose current or previous refresh token, or access token, has this hash. */
    public Optional<ConnectionRow> findByOAuthToken(String tokenHash) {
        return jdbc.query("""
            SELECT %s
              FROM learning_hub.external_assistant_connection
             WHERE kind = 'oauth'
               AND (access_token_hash = :hash OR refresh_token_hash = :hash OR previous_refresh_hash = :hash)
            """.formatted(COLUMNS), Map.of("hash", tokenHash), CONNECTION).stream().findFirst();
    }

    /** Whether this hash is the connection's previous refresh token, the one a rotation replaced. */
    public boolean isPreviousRefresh(long connectionId, String tokenHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT previous_refresh_hash = :hash
              FROM learning_hub.external_assistant_connection
             WHERE connection_id = :id
            """, new MapSqlParameterSource().addValue("id", connectionId).addValue("hash", tokenHash),
            Boolean.class));
    }

    /** The live oauth connection whose current, unexpired access token has this hash. */
    public Optional<ConnectionRow> findUsableAccess(String accessHash) {
        return jdbc.query("""
            SELECT %s
              FROM learning_hub.external_assistant_connection
             WHERE access_token_hash = :hash
               AND kind = 'oauth'
               AND access_expires_at > now()
               AND revoked_at IS NULL
               AND expires_at > now()
            """.formatted(COLUMNS), Map.of("hash", accessHash), CONNECTION).stream().findFirst();
    }

    /** Revokes the connection whoever's it is; false when it was already revoked. */
    public boolean revokeAny(long connectionId) {
        return jdbc.update("""
            UPDATE learning_hub.external_assistant_connection
               SET revoked_at = now()
             WHERE connection_id = :id
               AND revoked_at IS NULL
            """, Map.of("id", connectionId)) == 1;
    }

    /** Writes "lần dùng cuối" unless it is newer than {@code staleBefore}, so a busy connection writes it rarely. */
    public void touch(long connectionId, OffsetDateTime staleBefore) {
        jdbc.update("""
            UPDATE learning_hub.external_assistant_connection
               SET last_used_at = now()
             WHERE connection_id = :id
               AND (last_used_at IS NULL OR last_used_at < :staleBefore)
            """, new MapSqlParameterSource().addValue("id", connectionId).addValue("staleBefore", staleBefore));
    }
}
