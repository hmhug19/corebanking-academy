package vn.com.coreacademy.community.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Vòng đời một đề nghị review kiến trúc; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_review_proposals", schema = "learning_hub")
public class ReviewProposal extends CommunityRecord {
    @Id
    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "review_status", nullable = false, length = 30)
    private String reviewStatus;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "concluded_revision_id")
    private Long concludedRevisionId;

    @Column(name = "conclusion", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String conclusion;

    @Column(name = "concluded_by", length = 20)
    private String concludedBy;

    @Column(name = "concluded_at")
    private Instant concludedAt;
}
