package vn.com.coreacademy.assessment.entity;

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

/** Cấu hình bài thi của khóa học; thông số chấm được chụp sang quiz_attempts khi học viên bắt đầu. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "quizzes", schema = "learning_hub")
public class Quiz extends AssessmentRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "quiz_id", nullable = false)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "quiz_type", length = 20, nullable = false)
    private String quizType;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "selection_mode", length = 20, nullable = false)
    private String selectionMode;

    @Column(name = "question_count", nullable = false)
    private Integer questionCount;

    @Column(name = "shuffle_questions", nullable = false)
    private Boolean shuffleQuestions;

    @Column(name = "shuffle_options", nullable = false)
    private Boolean shuffleOptions;

    @Column(name = "time_limit_minutes", nullable = false)
    private Integer timeLimitMinutes;

    @Column(name = "open_at")
    private Instant openAt;

    @Column(name = "close_at")
    private Instant closeAt;

    @Column(name = "grace_seconds", nullable = false)
    private Integer graceSeconds;

    @Column(name = "pass_percent", nullable = false)
    private BigDecimal passPercent;

    @Column(name = "scoring_mode", length = 20, nullable = false)
    private String scoringMode;

    @Column(name = "score_method", length = 20, nullable = false)
    private String scoreMethod;

    @Column(name = "review_policy", length = 20, nullable = false)
    private String reviewPolicy;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts;

    @Column(name = "retake_wait_hours", nullable = false)
    private Integer retakeWaitHours;

    @Column(name = "allow_retake_after_pass", nullable = false)
    private Boolean allowRetakeAfterPass;

    @Column(name = "require_lessons_completed", nullable = false)
    private Boolean requireLessonsCompleted;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean deleted = false;
}
