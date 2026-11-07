package vn.com.coreacademy.learning.repository;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.learning.constants.LearningCodes.ReminderChannel;
import vn.com.coreacademy.learning.constants.LearningCodes.ReminderStatus;

/**
 * Thông báo đạt huy hiệu và lên cấp, ghi vào cùng bảng nhắc học (kind BADGE_EARNED hoặc LEVEL_UP). Khóa idempotency
 * theo người và đối tượng nên sự kiện lặp không tạo thêm. Dùng SQL thuần.
 */
@Repository
@RequiredArgsConstructor
public class GamificationNoticeRepository {
    private static final String SYSTEM = "SYSTEM";

    private final EntityManager entityManager;

    /** Tạo thông báo cho một người; trả true nếu đây là lần đầu (khóa đã có thì không ghi thêm). */
    public boolean create(String userId, String kind, String subject, String key) {
        return entityManager.createNativeQuery("""
            insert into learning_hub.learning_reminders (user_id, kind, subject, scheduled_at, due_at_snapshot, channel,
                status, attempt_count, idempotency_key, created_by, updated_by)
            select :user, :kind, :subject, now(), now(), :channel, :status, 0, :key, :system, :system
            where not exists (select 1 from learning_hub.learning_reminders where idempotency_key = :key)
            """).setParameter("user", userId).setParameter("kind", kind).setParameter("subject", subject)
            .setParameter("channel", ReminderChannel.IN_APP).setParameter("status", ReminderStatus.PENDING)
            .setParameter("key", key).setParameter("system", SYSTEM).executeUpdate() == 1;
    }
}
