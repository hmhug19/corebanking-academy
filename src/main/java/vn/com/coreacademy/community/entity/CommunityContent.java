package vn.com.coreacademy.community.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Định danh ổn định của một nội dung; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_contents", schema = "learning_hub")
public class CommunityContent extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "kind", nullable = false, length = 30)
    private String kind;

    @Column(name = "owner_org_id", nullable = false)
    private Long ownerOrgId;

    @Column(name = "author_user_id", nullable = false, length = 20)
    private String authorUserId;

    @Column(name = "maintainer_user_id", nullable = false, length = 20)
    private String maintainerUserId;

    @Column(name = "visibility", nullable = false, length = 30)
    private String visibility;

    @Column(name = "primary_guild_id")
    private Long primaryGuildId;

    @Column(name = "current_published_revision_id")
    private Long currentPublishedRevisionId;

    @Column(name = "current_draft_revision_id")
    private Long currentDraftRevisionId;

    @Column(name = "lifecycle_status", nullable = false, length = 30)
    private String lifecycleStatus;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 20)
    private String deletedBy;
}
