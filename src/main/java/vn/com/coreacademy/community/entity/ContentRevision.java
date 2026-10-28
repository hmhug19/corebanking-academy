package vn.com.coreacademy.community.entity;

import java.time.Instant;

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

/** Một phiên bản tiêu đề/nội dung/metadata; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_content_revisions", schema = "learning_hub")
public class ContentRevision extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body_markdown", nullable = false, columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String bodyMarkdown;

    @Column(name = "summary", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String summary;

    @Column(name = "context_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode contextJson;

    @Column(name = "revision_status", nullable = false, length = 30)
    private String revisionStatus;

    @Column(name = "change_note", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String changeNote;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by", length = 20)
    private String publishedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 20)
    private String deletedBy;
}
