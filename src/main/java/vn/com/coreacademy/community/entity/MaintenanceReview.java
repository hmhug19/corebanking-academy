package vn.com.coreacademy.community.entity;

import java.time.Instant;
import java.time.LocalDate;

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

/** Một lần rà soát tính cập nhật của nội dung; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_maintenance_reviews", schema = "learning_hub")
public class MaintenanceReview extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "maintenance_review_id", nullable = false)
    private Long maintenanceReviewId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "reviewer_user_id", nullable = false, length = 20)
    private String reviewerUserId;

    @Column(name = "outcome", nullable = false, length = 30)
    private String outcome;

    @Column(name = "note", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String note;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "next_review_due_at")
    private LocalDate nextReviewDueAt;
}
