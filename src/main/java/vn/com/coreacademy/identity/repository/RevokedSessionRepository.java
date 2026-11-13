package vn.com.coreacademy.identity.repository;

import java.sql.Timestamp;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Phiên đã đăng xuất, giữ đến khi token của phiên hết hạn (bảng {@code revoked_sessions}). */
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
     * Kết thúc phiên; false nếu phiên đã kết thúc trước đó. Dòng của token đã hết hạn được xóa luôn, nên bảng chỉ
     * giữ các phiên đăng xuất trong một vòng đời token.
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
