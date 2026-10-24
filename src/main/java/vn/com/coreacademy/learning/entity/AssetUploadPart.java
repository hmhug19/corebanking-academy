package vn.com.coreacademy.learning.entity;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "asset_upload_parts", schema = "learning_hub")
@IdClass(AssetUploadPart.Key.class)
public class AssetUploadPart extends LearningRecord {
    @Id
    @Column(name = "upload_id")
    private UUID uploadId;

    @Id
    @Column(name = "part_number")
    private Integer partNumber;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "part_status", length = 12)
    private String partStatus;

    @Column(name = "etag", length = 255)
    private String etag;

    @Column(name = "committed_at")
    private Instant committedAt;

    public record Key(UUID uploadId, Integer partNumber) implements Serializable {
    }
}
