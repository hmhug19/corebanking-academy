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
import vn.com.coreacademy.common.persistence.UpdatedEntity;

/**
 * Một lượt thi. Không dùng optimistic version vì học viên lưu nhiều câu song song; mọi thao tác ghi khóa dòng
 * (SELECT FOR UPDATE) để lưu đáp án, nộp bài và job tự nộp không chạy xen nhau.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "quiz_attempts", schema = "learning_hub")
public class QuizAttempt extends UpdatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attempt_id", nullable = false)
    private Long id;

    @Column(name = "quiz_id", nullable = false)
    private Long quizId;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "enrollment_id", nullable = false)
    private Long enrollmentId;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "submit_type", length = 20)
    private String submitType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "last_saved_at")
    private Instant lastSavedAt;

    @Column(name = "time_limit_minutes", nullable = false)
    private Integer timeLimitMinutes;

    @Column(name = "pass_percent", nullable = false)
    private BigDecimal passPercent;

    @Column(name = "scoring_mode", length = 20, nullable = false)
    private String scoringMode;

    @Column(name = "grace_seconds", nullable = false)
    private Integer graceSeconds;

    @Column(name = "total_questions", nullable = false)
    private Integer totalQuestions;

    @Column(name = "max_score", nullable = false)
    private BigDecimal maxScore;

    @Column(name = "correct_count")
    private Integer correctCount;

    @Column(name = "raw_score")
    private BigDecimal rawScore;

    @Column(name = "score_percent")
    private BigDecimal scorePercent;

    @Column(name = "passed")
    private Boolean passed;

    @Column(name = "graded_at")
    private Instant gradedAt;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "void_reason", columnDefinition = "text")
    private String voidReason;

    @Column(name = "voided_by", length = 20)
    private String voidedBy;

    @Column(name = "voided_at")
    private Instant voidedAt;
}
