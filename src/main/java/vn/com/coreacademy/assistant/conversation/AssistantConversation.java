package vn.com.coreacademy.assistant.conversation;

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
import vn.com.coreacademy.common.persistence.UpdatedEntity;

/** Hội thoại của một người dùng với trợ lý AI; chỉ chủ sở hữu đọc được. Các lượt nằm ở bảng riêng. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_conversations", schema = "learning_hub")
public class AssistantConversation extends UpdatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "conversation_id", nullable = false)
    private Long id;

    @Column(name = "owner_id", length = 20, nullable = false)
    private String ownerId;

    @Column(name = "title", length = 80, nullable = false)
    private String title;

    @Column(name = "context_course_id")
    private Long contextCourseId;

    @Column(name = "context_revision_id")
    private Long contextRevisionId;

    @Column(name = "context_lesson_id")
    private Long contextLessonId;

    @Column(name = "context_label", length = 255)
    private String contextLabel;

    @Column(name = "last_activity_time", nullable = false)
    private Instant lastActivityTime = Instant.now();
}
