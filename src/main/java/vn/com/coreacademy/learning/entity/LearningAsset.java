package vn.com.coreacademy.learning.entity;

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
import vn.com.coreacademy.learning.constants.LearningLimits;

/** Metadata tài nguyên; tệp thật lưu trong kho riêng. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assets", schema = "learning_hub")
public class LearningAsset extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "asset_id", nullable = false)
    private Long id;

    @Column(name = "owner_org_id", nullable = false)
    private Long ownerOrgId;

    @Column(name = "uploaded_by", length = 20, nullable = false)
    private String uploadedBy;

    @Column(name = "storage_type", length = 20, nullable = false)
    private String storageType;

    @Column(name = "storage_key", length = 255)
    private String storageKey;

    @Column(name = "external_url", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String externalUrl;

    @Column(name = "original_name", length = 255, nullable = false)
    private String originalName;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "mime_type", length = 100, nullable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "duration_seconds")
    private BigDecimal durationSeconds;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "media_type", length = 20)
    private String mediaType;

    @Column(name = "playback_key", length = 500)
    private String playbackKey;

    @Column(name = "playback_size_bytes")
    private Long playbackSizeBytes;

    @Column(name = "playback_mime_type", length = 100)
    private String playbackMimeType;

    @Column(name = "playback_checksum_sha256", length = 64)
    private String playbackChecksumSha256;

    @Column(name = "upload_id")
    private UUID uploadId;

    @Column(name = "s3_upload_id", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String s3UploadId;

    @Column(name = "upload_total_bytes")
    private Long uploadTotalBytes;

    @Column(name = "uploaded_bytes", nullable = false)
    private Long uploadedBytes = 0L;

    @Column(name = "chunk_size_bytes", nullable = false)
    private Integer chunkSizeBytes = LearningLimits.UPLOAD_PART_BYTES;

    @Column(name = "upload_expected_sha256", length = 64)
    private String uploadExpectedSha256;

    @Column(name = "upload_expires_at")
    private Instant uploadExpiresAt;

    @Column(name = "upload_completed_at")
    private Instant uploadCompletedAt;

    @Column(name = "processing_error", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String processingError;

    @Column(name = "processing_error_code", length = 50)
    private String processingErrorCode;

    @Column(name = "processing_attempts", nullable = false)
    private Integer processingAttempts = 0;

    @Column(name = "processing_lease_id")
    private UUID processingLeaseId;

    @Column(name = "processing_lease_expires_at")
    private Instant processingLeaseExpiresAt;

    @Column(name = "media_checked_at")
    private Instant mediaCheckedAt;

    @Column(name = "page_count_checked_at")
    private Instant pageCountCheckedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;
}
