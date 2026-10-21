package vn.com.coreacademy.learning.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một thông báo nhắc học trong ứng dụng, không gửi email tự động. Các cột ID liên kết là scalar, không tạo khóa
 * ngoại vật lý.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "learning_reminders", schema = "learning_hub")
public class LearningReminder extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reminder_id", nullable = false)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "due_at_snapshot", nullable = false)
    private Instant dueAtSnapshot;

    @Column(name = "channel", length = 20, nullable = false)
    private String channel;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "idempotency_key", length = 100, nullable = false)
    private String idempotencyKey;
}
