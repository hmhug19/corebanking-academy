package vn.com.coreacademy.identity.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.identity.entity.Function;

public interface FunctionRepository extends JpaRepository<Function, Long> {
    @Query("""
        select distinct f from Function f, RoleFunction rf
        where rf.functionId = f.id
          and rf.roleId = :roleId
          and f.active = true
        order by f.sortNo, f.id
        """)
    List<Function> findActiveForRole(@Param("roleId") Long roleId);

    Optional<Function> findByCodeAndActiveTrue(String code);
}
