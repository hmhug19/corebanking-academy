package vn.com.coreacademy.assistant.action;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.com.coreacademy.common.persistence.CreatedEntity;

/** Một đề xuất thay đổi dữ liệu của trợ lý; JSON nằm ở dạng chuỗi, {@link PendingActionStore} đọc và ghi chúng. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assistant_pending_actions", schema = "learning_hub")
public class AssistantPendingAction extends CreatedEntity {
    @Id
    @Column(name = "action_id", length = 40, nullable = false)
    private String id;

    @Column(name = "owner_id", length = 20, nullable = false)
    private String ownerId;

    @Column(name = "tool", length = 60, nullable = false)
    private String tool;

    @Column(name = "arguments", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String arguments;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "changes", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String changes = "[]";

    @Column(name = "status", length = 20, nullable = false)
    private String status = PendingActionStore.PENDING;

    @Column(name = "result_message", length = 500)
    private String resultMessage;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resolved_time")
    private Instant resolvedTime;
}
