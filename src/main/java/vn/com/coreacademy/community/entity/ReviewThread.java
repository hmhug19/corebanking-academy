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

/** Một luồng góp ý gắn đúng revision/file; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_review_threads", schema = "learning_hub")
public class ReviewThread extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "thread_id", nullable = false)
    private Long threadId;

    @Column(name = "proposal_content_id", nullable = false)
    private Long proposalContentId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "revision_asset_id")
    private Long revisionAssetId;

    @Column(name = "anchor_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode anchorJson;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "resolved_by", length = 20)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String resolutionNote;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
}
