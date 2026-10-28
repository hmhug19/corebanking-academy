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

/** Một báo cáo vi phạm/sai/lỗi thời do thành viên gửi; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_reports", schema = "learning_hub")
public class CommunityReport extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "reporter_user_id", nullable = false, length = 20)
    private String reporterUserId;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "comment_id")
    private Long commentId;

    @Column(name = "reason_code", nullable = false, length = 100)
    private String reasonCode;

    @Column(name = "description", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String description;

    @Column(name = "report_status", nullable = false, length = 30)
    private String reportStatus;

    @Column(name = "assigned_moderator_user_id", length = 20)
    private String assignedModeratorUserId;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
