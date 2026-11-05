package vn.com.coreacademy.identity.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import vn.com.coreacademy.identity.enums.AccessRequestKind;
import vn.com.coreacademy.identity.enums.AccessRequestStatus;

/**
 * A visitor's request to register or to reset a password, waiting for an administrator. The password hash of a
 * registration is write-only: no DTO carries it and {@link #toString()} leaves it out.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "access_requests", schema = "learning_hub")
public class AccessRequest extends UpdatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", length = 20, nullable = false)
    private AccessRequestKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private AccessRequestStatus status = AccessRequestStatus.PENDING;

    @Column(name = "staff_code", length = 20, nullable = false)
    private String staffCode;

    @Column(name = "full_name", length = 255)
    private String fullName;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "target_user_id", length = 20)
    private String targetUserId;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "resolved_by", length = 20)
    private String resolvedBy;

    @Column(name = "resolved_time")
    private Instant resolvedTime;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;

    @Override
    public String toString() {
        return "AccessRequest[id=" + id + ", kind=" + kind + ", status=" + status + "]";
    }
}
