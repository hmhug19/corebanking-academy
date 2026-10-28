package vn.com.coreacademy.community.entity;

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

/** Quan hệ câu hỏi trùng/tương tự; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_question_links", schema = "learning_hub")
public class QuestionLink extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_link_id", nullable = false)
    private Long questionLinkId;

    @Column(name = "source_question_id", nullable = false)
    private Long sourceQuestionId;

    @Column(name = "target_question_id", nullable = false)
    private Long targetQuestionId;

    @Column(name = "relation_type", nullable = false, length = 30)
    private String relationType;

    @Column(name = "reason", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String reason;
}
