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

/** Quyền khai thác khóa học cho đơn vị nhận. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "course_shares", schema = "learning_hub")
public class CourseShare extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "share_id", nullable = false)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "include_descendants", nullable = false)
    private Boolean includeDescendants;

    @Column(name = "can_assign", nullable = false)
    private Boolean canAssign;

    @Column(name = "can_self_enroll", nullable = false)
    private Boolean canSelfEnroll;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "shared_by", length = 20, nullable = false)
    private String sharedBy;

    @Column(name = "shared_at", nullable = false)
    private Instant sharedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;
}
