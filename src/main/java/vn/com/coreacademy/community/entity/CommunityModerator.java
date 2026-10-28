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

/** Quyền điều phối Community trong đơn vị; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_moderators", schema = "learning_hub")
public class CommunityModerator extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "moderator_id", nullable = false)
    private Long moderatorId;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "granted_by", nullable = false, length = 20)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
