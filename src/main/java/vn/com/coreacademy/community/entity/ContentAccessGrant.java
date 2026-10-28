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

/** Quyền đọc/sửa bổ sung của nội dung restricted; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_content_access_grants", schema = "learning_hub")
public class ContentAccessGrant extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "user_id", length = 20)
    private String userId;

    @Column(name = "guild_id")
    private Long guildId;

    @Column(name = "permission", nullable = false, length = 30)
    private String permission;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
