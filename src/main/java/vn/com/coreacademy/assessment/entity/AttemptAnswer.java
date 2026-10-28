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

/** Đáp án của một câu trong lượt; isCorrect/scoreAwarded NULL cho tới khi chấm. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "attempt_answers", schema = "learning_hub")
public class AttemptAnswer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "answer_id", nullable = false)
    private Long id;

    @Column(name = "attempt_question_id", nullable = false)
    private Long attemptQuestionId;

    @Column(name = "selected_option_ids", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String selectedOptionIds;

    @Column(name = "is_correct")
    private Boolean correct;

    @Column(name = "score_awarded")
    private BigDecimal scoreAwarded;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    @Column(name = "changed_at")
    private Instant changedAt;

    @Column(name = "change_count", nullable = false)
    private Integer changeCount = 0;
}
