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

/** Phản hồi của chủ lượt AI, không lưu lại câu trả lời riêng tư; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_ai_feedback", schema = "learning_hub")
public class AiFeedback extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feedback_id", nullable = false)
    private Long feedbackId;

    @Column(name = "turn_id", nullable = false)
    private Long turnId;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "helpful", nullable = false)
    private Boolean helpful;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "note", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String note;
}
