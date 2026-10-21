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

/**
 * Điểm nối module thi; chưa tạo liên kết khi module thi chưa triển khai. Các cột ID liên kết là scalar, không tạo
 * khóa ngoại vật lý.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "course_assessments", schema = "learning_hub")
public class CourseAssessment extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "link_id", nullable = false)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "assessment_revision_id", nullable = false)
    private Long assessmentRevisionId;

    @Column(name = "placement", length = 20, nullable = false)
    private String placement;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired;

    @Column(name = "pass_score_percent", nullable = false)
    private BigDecimal passScorePercent;

    @Column(name = "max_attempts")
    private Integer maxAttempts;

    @Column(name = "allow_retake", nullable = false)
    private Boolean allowRetake;

    @Column(name = "opens_at")
    private Instant opensAt;

    @Column(name = "closes_at")
    private Instant closesAt;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;
}
