package vn.com.coreacademy.assessment.entity;

import java.math.BigDecimal;

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
import vn.com.coreacademy.common.persistence.CreatedEntity;

/**
 * Snapshot câu hỏi tại thời điểm phát đề. Hai cột JSON giữ dạng chuỗi và được đọc/ghi qua AttemptJson để
 * kiểm soát kiểu phần tử; correctOptionIds không bao giờ trả cho học viên khi lượt đang làm.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "attempt_questions", schema = "learning_hub")
public class AttemptQuestion extends CreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "attempt_question_id", nullable = false)
    private Long id;

    @Column(name = "attempt_id", nullable = false)
    private Long attemptId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "question_version", nullable = false)
    private Integer questionVersion;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "question_type", length = 20, nullable = false)
    private String questionType;

    @Column(name = "content_snapshot", columnDefinition = "text", nullable = false)
    private String contentSnapshot;

    @Column(name = "options_snapshot", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String optionsSnapshot;

    @Column(name = "correct_option_ids", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String correctOptionIds;

    @Column(name = "explanation_snapshot", columnDefinition = "text")
    private String explanationSnapshot;

    @Column(name = "points", nullable = false)
    private BigDecimal points;

    @Column(name = "competency_id")
    private Long competencyId;

    @Column(name = "difficulty", length = 10)
    private String difficulty;

    @Column(name = "is_flagged", nullable = false)
    private Boolean flagged = false;
}
