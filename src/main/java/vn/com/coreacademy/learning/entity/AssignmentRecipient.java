package vn.com.coreacademy.learning.entity;

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

/** Một người thực nhận khóa trong một đợt giao. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assignment_recipients", schema = "learning_hub")
public class AssignmentRecipient extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recipient_id", nullable = false)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "enrollment_id", nullable = false)
    private Long enrollmentId;

    @Column(name = "org_id_snapshot")
    private Long orgIdSnapshot;

    @Column(name = "team_id_snapshot")
    private Long teamIdSnapshot;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    @Column(name = "effective_start_at", nullable = false)
    private Instant effectiveStartAt;

    @Column(name = "effective_due_at")
    private Instant effectiveDueAt;

    @Column(name = "requirement", length = 20, nullable = false)
    private String requirement;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String revokeReason;
}
