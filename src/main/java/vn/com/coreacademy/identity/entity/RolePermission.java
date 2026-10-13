package vn.com.coreacademy.identity.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.CreatedEntity;


@Entity
@Table(name = "role_permission", schema = "learning_hub")
public class RolePermission extends CreatedEntity {
    @EmbeddedId
    private RolePermissionId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", referencedColumnName = "role_id", insertable = false, updatable = false)
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", referencedColumnName = "permission_id", insertable = false, updatable = false)
    private Permission permission;

    public RolePermissionId getId() { return id; }
    public void setId(RolePermissionId id) { this.id = id; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Permission getPermission() { return permission; }
    public void setPermission(Permission permission) { this.permission = permission; }

}
