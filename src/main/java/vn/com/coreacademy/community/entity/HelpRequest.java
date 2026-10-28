package vn.com.coreacademy.community.entity;

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

/** Mời người hoặc Guild hỗ trợ câu hỏi; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_help_requests", schema = "learning_hub")
public class HelpRequest extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "help_request_id", nullable = false)
    private Long helpRequestId;

    @Column(name = "question_content_id", nullable = false)
    private Long questionContentId;

    @Column(name = "requested_by", nullable = false, length = 20)
    private String requestedBy;

    @Column(name = "target_user_id", length = 20)
    private String targetUserId;

    @Column(name = "target_guild_id")
    private Long targetGuildId;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "responded_by", length = 20)
    private String respondedBy;

    @Column(name = "responded_at")
    private Instant respondedAt;
}
