package vn.com.coreacademy.identity.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import java.util.List;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, Long> {
    @Query("select a from RoleAssignment a join fetch a.role where a.user.id = :userId and a.active = true and a.role.active = true")
    List<RoleAssignment> findActiveForUser(@Param("userId") String userId);
}
