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

/** Một quyền đọc/cộng tác bộ sưu tập restricted; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_collection_access_grants", schema = "learning_hub")
public class CollectionAccessGrant extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grant_id", nullable = false)
    private Long grantId;

    @Column(name = "collection_id", nullable = false)
    private Long collectionId;

    @Column(name = "user_id", length = 20)
    private String userId;

    @Column(name = "guild_id")
    private Long guildId;

    @Column(name = "permission", nullable = false, length = 30)
    private String permission;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "status", nullable = false, length = 30)
    private String status;
}
