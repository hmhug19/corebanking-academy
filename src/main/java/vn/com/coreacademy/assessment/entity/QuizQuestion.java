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
import vn.com.coreacademy.common.persistence.CreatedEntity;

/** Một câu trong pool của đề; points NULL nghĩa là dùng điểm mặc định của câu hỏi. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "quiz_questions", schema = "learning_hub")
public class QuizQuestion extends CreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "quiz_question_id", nullable = false)
    private Long id;

    @Column(name = "quiz_id", nullable = false)
    private Long quizId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    @Column(name = "points")
    private BigDecimal points;

    @Column(name = "created_by", length = 20, nullable = false)
    private String createdBy;
}
