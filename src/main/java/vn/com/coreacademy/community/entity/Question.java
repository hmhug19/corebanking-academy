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

/** Thuộc tính riêng của một câu hỏi; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity(name = "CommunityQuestion")
@Table(name = "community_questions", schema = "learning_hub")
public class Question extends CommunityRecord {
    @Id
    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "accepted_answer_content_id")
    private Long acceptedAnswerContentId;

    @Column(name = "accepted_answer_revision_id")
    private Long acceptedAnswerRevisionId;

    @Column(name = "accepted_by", length = 20)
    private String acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "resolution_status", nullable = false, length = 30)
    private String resolutionStatus;

    @Column(name = "closed_reason", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String closedReason;
}
