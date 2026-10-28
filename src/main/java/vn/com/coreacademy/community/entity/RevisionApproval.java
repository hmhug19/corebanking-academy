package vn.com.coreacademy.community.entity;

import java.time.Instant;

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

/** Một quyết định duyệt bản tài liệu cụ thể; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_revision_approvals", schema = "learning_hub")
public class RevisionApproval extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "reviewer_user_id", nullable = false, length = 20)
    private String reviewerUserId;

    @Column(name = "decision", nullable = false, length = 30)
    private String decision;

    @Column(name = "note", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String note;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;
}
