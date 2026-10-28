package vn.com.coreacademy.community.entity;

import java.math.BigDecimal;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Metadata một tệp Community, không lưu bytes trong DB; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_assets", schema = "learning_hub")
public class CommunityAsset extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "owner_org_id", nullable = false)
    private Long ownerOrgId;

    @Column(name = "uploaded_by", nullable = false, length = 20)
    private String uploadedBy;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "storage_key", length = 1000)
    private String storageKey;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "media_type", nullable = false, length = 30)
    private String mediaType;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "duration_seconds", columnDefinition = "numeric(12,3)")
    private BigDecimal durationSeconds;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "media_checked_at")
    private Instant mediaCheckedAt;

    @Column(name = "playback_key", length = 1000)
    private String playbackKey;

    @Column(name = "playback_size_bytes")
    private Long playbackSizeBytes;

    @Column(name = "playback_mime_type", length = 100)
    private String playbackMimeType;

    @Column(name = "playback_checksum_sha256", length = 64)
    private String playbackChecksumSha256;

    @Column(name = "upload_id", nullable = false)
    private UUID uploadId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "s3_upload_id", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String s3UploadId;

    @Column(name = "upload_total_bytes", nullable = false)
    private Long uploadTotalBytes;

    @Column(name = "uploaded_bytes", nullable = false)
    private Long uploadedBytes;

    @Column(name = "chunk_size_bytes", nullable = false)
    private Integer chunkSizeBytes;

    @Column(name = "upload_expires_at", nullable = false)
    private Instant uploadExpiresAt;

    @Column(name = "upload_completed_at")
    private Instant uploadCompletedAt;

    @Column(name = "processing_attempts", nullable = false)
    private Integer processingAttempts;

    @Column(name = "processing_available_at")
    private Instant processingAvailableAt;

    @Column(name = "processing_lease_token")
    private UUID processingLeaseToken;

    @Column(name = "processing_lease_expires_at")
    private Instant processingLeaseExpiresAt;

    @Column(name = "processing_error_code", length = 100)
    private String processingErrorCode;

    @Column(name = "cleanup_status", nullable = false, length = 30)
    private String cleanupStatus;

    @Column(name = "cleanup_attempts", nullable = false)
    private Integer cleanupAttempts;

    @Column(name = "cleanup_last_error_code", length = 100)
    private String cleanupLastErrorCode;

    @Column(name = "cleanup_after")
    private Instant cleanupAfter;

    @Column(name = "cleanup_lease_token")
    private UUID cleanupLeaseToken;

    @Column(name = "cleanup_lease_expires_at")
    private Instant cleanupLeaseExpiresAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 20)
    private String deletedBy;
}
