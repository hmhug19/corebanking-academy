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

/** Trạng thái xử lý một yêu cầu bổ sung tri thức; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_knowledge_requests", schema = "learning_hub")
public class KnowledgeRequest extends CommunityRecord {
    @Id
    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "request_status", nullable = false, length = 30)
    private String requestStatus;

    @Column(name = "claimed_by", length = 20)
    private String claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "closed_reason", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String closedReason;
}
