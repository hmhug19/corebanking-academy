package vn.com.coreacademy.learning.entity;

import java.math.BigDecimal;
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

/** Một lần học cá nhân; giữ lịch sử và phiên bản riêng. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "course_enrollments", schema = "learning_hub")
public class CourseEnrollment extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "enrollment_id", nullable = false)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "cycle_no", nullable = false)
    private Integer cycleNo;

    @Column(name = "enrollment_origin", length = 20, nullable = false)
    private String enrollmentOrigin;

    @Column(name = "self_enrolled_at")
    private Instant selfEnrolledAt;

    @Column(name = "org_id_snapshot")
    private Long orgIdSnapshot;

    @Column(name = "team_id_snapshot")
    private Long teamIdSnapshot;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "last_access_at")
    private Instant lastAccessAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "progress_percent", nullable = false)
    private BigDecimal progressPercent;

    @Column(name = "last_lesson_id")
    private Long lastLessonId;

    @Column(name = "last_block_id")
    private Long lastBlockId;
}
