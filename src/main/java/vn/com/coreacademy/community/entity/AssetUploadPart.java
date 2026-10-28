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

/** Manifest từng phần upload đã ACK/reserved, không lưu bytes; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity(name = "CommunityAssetUploadPart")
@Table(name = "community_asset_upload_parts", schema = "learning_hub")
public class AssetUploadPart extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "part_id", nullable = false)
    private Long partId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "part_number", nullable = false)
    private Integer partNumber;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "etag", length = 255)
    private String etag;

    @Column(name = "part_status", nullable = false, length = 30)
    private String partStatus;

    @Column(name = "reservation_token")
    private UUID reservationToken;

    @Column(name = "reservation_expires_at")
    private Instant reservationExpiresAt;
}
