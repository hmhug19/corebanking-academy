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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Một lượt đã được chấm lại theo đáp án mới; giữ giá trị trước/sau để truy vết và xử lý khiếu nại. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "attempt_regrades", schema = "learning_hub")
public class AttemptRegrade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "regrade_id", nullable = false)
    private Long id;

    @Column(name = "batch_id", length = 36, nullable = false)
    private String batchId;

    @Column(name = "quiz_id", nullable = false)
    private Long quizId;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "reason", columnDefinition = "text", nullable = false)
    private String reason;

    @Column(name = "old_correct_option_ids", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String oldCorrectOptionIds;

    @Column(name = "new_correct_option_ids", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String newCorrectOptionIds;

    @Column(name = "old_correct_count")
    private Integer oldCorrectCount;

    @Column(name = "new_correct_count")
    private Integer newCorrectCount;

    @Column(name = "old_raw_score")
    private BigDecimal oldRawScore;

    @Column(name = "new_raw_score")
    private BigDecimal newRawScore;

    @Column(name = "old_score_percent")
    private BigDecimal oldScorePercent;

    @Column(name = "new_score_percent")
    private BigDecimal newScorePercent;

    @Column(name = "old_passed")
    private Boolean oldPassed;

    @Column(name = "new_passed")
    private Boolean newPassed;

    @Column(name = "regraded_by", length = 20, nullable = false)
    private String regradedBy;

    @Column(name = "regraded_at", nullable = false)
    private Instant regradedAt;
}
