package vn.com.coreacademy.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.entity.Team;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_roles", schema = "learning_hub")
public class RoleAssignment extends UpdatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ur_id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Role role;

    // Vai trò và scope thuộc cùng grant; orgId/teamId có ý nghĩa theo scopeType tương ứng.
    @Column(name = "scope", length = 15, nullable = false)
    @Enumerated(EnumType.STRING)
    private ScopeType scopeType;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "granted_by", length = 20)
    private String grantedBy;

    // Quan hệ chỉ đọc: ghi bằng ID phía trên; NO_CONSTRAINT giữ mapping mà không tạo FK vật lý.
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
        name = "org_id",
        referencedColumnName = "org_id",
        insertable = false,
        updatable = false,
        foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)
    )
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "team_id",
        insertable = false,
        updatable = false,
        foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)
    )
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(
        name = "granted_by",
        referencedColumnName = "user_id",
        insertable = false,
        updatable = false,
        foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT)
    )
    private UserAccount grantor;
}
