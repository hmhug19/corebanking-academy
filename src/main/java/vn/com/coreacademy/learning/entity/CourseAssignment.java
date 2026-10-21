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

/**
 * Một đợt giao khóa học; requestHash phát hiện tái dùng khóa khác payload. Các cột ID liên kết là scalar, không tạo
 * khóa ngoại vật lý.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "course_assignments", schema = "learning_hub")
public class CourseAssignment extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "assignment_id", nullable = false)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "assigning_org_id", nullable = false)
    private Long assigningOrgId;

    @Column(name = "assigned_by", length = 20, nullable = false)
    private String assignedBy;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "requirement", length = 20, nullable = false)
    private String requirement;

    @Column(name = "enrollment_mode", length = 20, nullable = false)
    private String enrollmentMode;

    @Column(name = "reminder_enabled", nullable = false)
    private Boolean reminderEnabled;

    @Column(name = "reminder_days_before", nullable = false)
    private Integer reminderDaysBefore;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "idempotency_key", length = 100, nullable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;
}
