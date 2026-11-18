package vn.com.coreacademy.assistant.conversation;

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

/** Một lượt hỏi-đáp, lưu đủ văn bản. JSON nằm ở dạng chuỗi; {@link ConversationService} đọc và ghi chúng. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_turns", schema = "learning_hub")
public class AssistantTurn extends CreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "turn_id", nullable = false)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(name = "surface", length = 20, nullable = false)
    private String surface;

    @Column(name = "question", nullable = false, columnDefinition = "text")
    private String question;

    @Column(name = "answer", columnDefinition = "text")
    private String answer;

    @Column(name = "citations", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String citations = "[]";

    @Column(name = "tool_names", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String toolNames = "[]";

    @Column(name = "navigation")
    @JdbcTypeCode(SqlTypes.JSON)
    private String navigation;

    @Column(name = "action_id", length = 40)
    private String actionId;

    @Column(name = "skill", length = 40)
    private String skill;

    @Column(name = "prompt_version", length = 40)
    private String promptVersion;

    @Column(name = "retrieval_trace", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String retrievalTrace = "[]";

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "first_token_ms")
    private Integer firstTokenMs;

    @Column(name = "duration_ms")
    private Integer durationMs;

    /** Tin nhắn bước của Soạn cùng trợ lý: phiên và bước mà thẻ "Đã chốt" trỏ tới; null với lượt thường. */
    @Column(name = "drafting_session_id")
    private Long draftingSessionId;

    @Column(name = "drafting_step")
    private Short draftingStep;
}
