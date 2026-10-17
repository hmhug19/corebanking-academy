package vn.com.coreacademy.audit.entity;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.com.coreacademy.common.persistence.CreatedEntity;
import vn.com.coreacademy.identity.entity.UserAccount;

@Getter
@Setter
@NoArgsConstructor
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

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id", length = 100)
    private String entityId;

    @Column(name = "details", nullable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details = new LinkedHashMap<>();

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
        name = "actor_id",
        referencedColumnName = "user_id",
        insertable = false,
        updatable = false,
        foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)
    )
    private UserAccount actor;
}
