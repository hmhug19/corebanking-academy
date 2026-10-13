package vn.com.coreacademy.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.identity.entity.RolePermission;
import vn.com.coreacademy.identity.entity.RolePermissionId;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermissionId> {
}
