package vn.com.coreacademy.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.identity.entity.Permission;
import java.util.List;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    @Query(value = "select distinct p.code from learning_hub.role_permission rp "
            + "join learning_hub.permission p on p.permission_id=rp.permission_id where rp.role_id=:roleId", nativeQuery = true)
    List<String> findCodesForRole(@Param("roleId") Long roleId);
}
