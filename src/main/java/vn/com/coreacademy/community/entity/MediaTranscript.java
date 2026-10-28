package vn.com.coreacademy.community.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Phiên bản bản chép lời video được biên tập trước publish; ID scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_media_transcripts", schema = "learning_hub")
public class MediaTranscript extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transcript_id", nullable = false)
    private Long transcriptId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "language", nullable = false, length = 100)
    private String language;

    @Column(name = "body_text", nullable = false, columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String bodyText;

    @Column(name = "segments_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode segmentsJson;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
}
