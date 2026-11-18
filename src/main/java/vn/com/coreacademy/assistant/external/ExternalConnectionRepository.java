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
    public record ConnectionRow(long id, String userId, String name, String tokenPrefix, OffsetDateTime createTime,
                                OffsetDateTime expiresAt, OffsetDateTime lastUsedAt, OffsetDateTime revokedAt) {
    }

    private static final RowMapper<ConnectionRow> CONNECTION = (rs, row) -> new ConnectionRow(
        rs.getLong("connection_id"), rs.getString("user_id"), rs.getString("name"), rs.getString("token_prefix"),
        rs.getObject("create_time", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
        rs.getObject("last_used_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class));

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
            SELECT connection_id, user_id, name, token_prefix, create_time, expires_at, last_used_at, revoked_at
              FROM learning_hub.external_assistant_connection
             WHERE connection_id = :id
            """, Map.of("id", connectionId), CONNECTION).stream().findFirst();
    }

    /** The user's connections that are not revoked, newest first; expired ones stay listed until revoked. */
    public List<ConnectionRow> listOwn(String userId) {
        return jdbc.query("""
            SELECT connection_id, user_id, name, token_prefix, create_time, expires_at, last_used_at, revoked_at
              FROM learning_hub.external_assistant_connection
             WHERE user_id = :user
               AND revoked_at IS NULL
             ORDER BY create_time DESC, connection_id DESC
            """, Map.of("user", userId), CONNECTION);
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
            SELECT connection_id, user_id, name, token_prefix, create_time, expires_at, last_used_at, revoked_at
              FROM learning_hub.external_assistant_connection
             WHERE token_hash = :hash
               AND revoked_at IS NULL
               AND expires_at > now()
            """, Map.of("hash", tokenHash), CONNECTION).stream().findFirst();
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
