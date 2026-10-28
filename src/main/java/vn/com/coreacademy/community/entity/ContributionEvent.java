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

/** Một bút toán điểm đóng góp bất biến hoặc đảo điểm; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_contribution_events", schema = "learning_hub")
public class ContributionEvent extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contribution_event_id", nullable = false)
    private Long contributionEventId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "source_event_id", nullable = false)
    private Long sourceEventId;

    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;

    @Column(name = "points_delta", nullable = false)
    private Integer pointsDelta;

    @Column(name = "policy_version", nullable = false)
    private Integer policyVersion;

    @Column(name = "reverses_event_id")
    private Long reversesEventId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
