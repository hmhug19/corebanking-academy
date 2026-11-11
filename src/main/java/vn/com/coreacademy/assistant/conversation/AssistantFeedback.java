package vn.com.coreacademy.assistant.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.com.coreacademy.common.persistence.UpdatedEntity;

/** Đánh giá 👍/👎 của chủ hội thoại cho một lượt; khoá là id lượt nên mỗi lượt chỉ một đánh giá, sửa được. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_feedback", schema = "learning_hub")
public class AssistantFeedback extends UpdatedEntity {
    @Id
    @Column(name = "turn_id", nullable = false)
    private Long turnId;

    @Column(name = "owner_id", length = 20, nullable = false)
    private String ownerId;

    @Column(name = "helpful", nullable = false)
    private boolean helpful;

    @Column(name = "reason_code", length = 20)
    private String reasonCode;

    @Column(name = "note", length = 2000)
    private String note;
}
