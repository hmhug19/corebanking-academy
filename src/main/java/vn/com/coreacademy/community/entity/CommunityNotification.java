package vn.com.coreacademy.community.entity;

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

/** Một thông báo in-app cho một người; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_notifications", schema = "learning_hub")
public class CommunityNotification extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    @Column(name = "recipient_user_id", nullable = false, length = 20)
    private String recipientUserId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "delivery_status", nullable = false, length = 30)
    private String deliveryStatus;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "delivery_mode", nullable = false, length = 30)
    private String deliveryMode;
}
