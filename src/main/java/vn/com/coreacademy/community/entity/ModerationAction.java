package vn.com.coreacademy.community.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Lịch sử hành động xử lý báo cáo; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_moderation_actions", schema = "learning_hub")
public class ModerationAction extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "action_id", nullable = false)
    private Long actionId;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(name = "actor_user_id", nullable = false, length = 20)
    private String actorUserId;

    @Column(name = "action_type", nullable = false, length = 30)
    private String actionType;

    @Column(name = "reason", nullable = false, columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String reason;

    @Column(name = "previous_state_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode previousStateJson;

    @Column(name = "new_state_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode newStateJson;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
