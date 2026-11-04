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

/**
 * Câu hỏi dùng chung trong chương trình; courseId chỉ giữ nguồn của dữ liệu cũ.
 * contentVersion tăng khi sửa câu đã từng được phát đề; rowVersion chống ghi đè đồng thời.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "questions", schema = "learning_hub")
public class Question extends AssessmentRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id", nullable = false)
    private Long id;

    @Column(name = "code", length = 50)
    private String code;

    @Column(name = "program_id")
    private Long programId;

    @Column(name = "course_id")
    private Long courseId;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "competency_id", nullable = false)
    private Long competencyId;

    @Column(name = "question_type", length = 20, nullable = false)
    private String questionType;

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "explanation", columnDefinition = "text")
    private String explanation;

    @Column(name = "difficulty", length = 10, nullable = false)
    private String difficulty;

    @Column(name = "points", nullable = false)
    private BigDecimal points;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "content_version", nullable = false)
    private Integer contentVersion = 1;

    @Column(name = "is_deleted", nullable = false)
    private Boolean deleted = false;
}
