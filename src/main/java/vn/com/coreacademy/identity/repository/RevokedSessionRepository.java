package vn.com.coreacademy.identity.repository;

import java.sql.Timestamp;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Sessions ended by logout, kept until their token would have expired (table {@code revoked_sessions}). */
@Repository
@RequiredArgsConstructor
public class RevokedSessionRepository {
    private final JdbcTemplate jdbc;

    public boolean isRevoked(String sessionId) {
        Boolean revoked = jdbc.queryForObject(
            "select exists(select 1 from learning_hub.revoked_sessions where session_id = ?)", Boolean.class,
            sessionId);

        return Boolean.TRUE.equals(revoked);
    }

    /**
     * Ends the session; false when it had already ended. Rows of expired tokens are deleted on the way, so the table
     * holds at most the sessions logged out within one token lifetime.
     */
    public boolean revoke(String sessionId, String userId, Instant expiresAt) {
        jdbc.update("delete from learning_hub.revoked_sessions where expires_at < CURRENT_TIMESTAMP");

        return jdbc.update("""
            insert into learning_hub.revoked_sessions(session_id, user_id, expires_at)
            values (?, ?, ?)
            on conflict (session_id) do nothing
            """, sessionId, userId, Timestamp.from(expiresAt)) == 1;
    }
}
