package vn.com.coreacademy.assessment.entity;

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

/** Phương án trả lời; ID được chụp vào đề đã phát nên sửa tại chỗ và chỉ xóa mềm. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "question_options", schema = "learning_hub")
public class QuestionOption extends UpdatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "option_id", nullable = false)
    private Long id;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "content", columnDefinition = "text", nullable = false)
    private String content;

    @Column(name = "is_correct", nullable = false)
    private Boolean correct;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    @Column(name = "is_deleted", nullable = false)
    private Boolean deleted = false;
}
