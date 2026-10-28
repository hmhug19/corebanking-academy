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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Một bình luận hoặc phản hồi bình luận; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_comments", schema = "learning_hub")
public class CommunityComment extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "comment_id", nullable = false)
    private Long commentId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "author_user_id", nullable = false, length = 20)
    private String authorUserId;

    @Column(name = "parent_comment_id")
    private Long parentCommentId;

    @Column(name = "revision_id")
    private Long revisionId;

    @Column(name = "review_thread_id")
    private Long reviewThreadId;

    @Column(name = "body_markdown", nullable = false, columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String bodyMarkdown;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 20)
    private String deletedBy;
}
