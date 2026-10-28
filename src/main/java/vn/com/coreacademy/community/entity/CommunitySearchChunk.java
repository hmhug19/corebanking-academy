package vn.com.coreacademy.community.entity;

import java.math.BigDecimal;

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

/**
 * Đoạn index của revision để search/RAG có ACL, dùng ID scalar.
 * Cột embedding vector(1024) được dành cho native query pgvector, chưa map thành kiểu JPA không tương thích.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_search_chunks", schema = "learning_hub")
public class CommunitySearchChunk extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chunk_id", nullable = false)
    private Long chunkId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "transcript_id")
    private Long transcriptId;

    @Column(name = "chunk_no", nullable = false)
    private Integer chunkNo;

    @Column(name = "body_text", nullable = false, columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String bodyText;

    @Column(name = "embedding_model", nullable = false, length = 100)
    private String embeddingModel;

    @Column(name = "index_status", nullable = false, length = 30)
    private String indexStatus;

    @Column(name = "start_seconds", columnDefinition = "numeric(12,3)")
    private BigDecimal startSeconds;

    @Column(name = "end_seconds", columnDefinition = "numeric(12,3)")
    private BigDecimal endSeconds;
}
