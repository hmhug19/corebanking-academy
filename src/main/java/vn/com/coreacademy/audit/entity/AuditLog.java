package vn.com.coreacademy.audit.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.CreatedEntity;
import java.util.Map;
import java.util.LinkedHashMap;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.com.coreacademy.identity.entity.UserAccount;

@Entity
@Table(name = "audit_log", schema = "learning_hub")
public class AuditLog extends CreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_id", nullable = false)
    private Long id;

    @Column(name = "actor_id", length = 20)
    private String actorId;

    @Column(name = "action", length = 100, nullable = false)
    private String action;

    @Column(name = "entity_type", length = 50, nullable = false)
    private String entityType;

    @Column(name = "entity_id", length = 100)
    private String entityId;

    @Column(name = "details", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details = new LinkedHashMap<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "actor_id", referencedColumnName = "user_id", insertable = false, updatable = false)
    private UserAccount actor;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }

    public UserAccount getActor() { return actor; }
    public void setActor(UserAccount actor) { this.actor = actor; }

}
