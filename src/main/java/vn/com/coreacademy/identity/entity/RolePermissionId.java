package vn.com.coreacademy.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class RolePermissionId implements Serializable {
    private static final long serialVersionUID = 1L;
    @Column(name = "role_id", nullable = false)
    private Long roleId;
    @Column(name = "permission_id", nullable = false)
    private Long permissionId;

    public RolePermissionId() {}
    public RolePermissionId(Long roleId, Long permissionId) {
        this.roleId = roleId;
        this.permissionId = permissionId;
    }

    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }
    public Long getPermissionId() { return permissionId; }
    public void setPermissionId(Long permissionId) { this.permissionId = permissionId; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RolePermissionId that)) return false;
        return Objects.equals(roleId, that.roleId) && Objects.equals(permissionId, that.permissionId);
    }
    @Override public int hashCode() { return Objects.hash(roleId, permissionId); }
}
