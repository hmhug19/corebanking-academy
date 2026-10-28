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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Quyết định trao/thu hồi huy hiệu theo đúng phiên bản luật; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_badge_awards", schema = "learning_hub")
public class BadgeAward extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "award_id", nullable = false)
    private Long awardId;

    @Column(name = "badge_id", nullable = false)
    private Long badgeId;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "awarded_at", nullable = false)
    private Instant awardedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String revokeReason;

    @Column(name = "source_event_id", nullable = false)
    private Long sourceEventId;
}
