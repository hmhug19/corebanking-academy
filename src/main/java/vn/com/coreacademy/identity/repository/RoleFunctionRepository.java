package vn.com.coreacademy.identity.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.identity.entity.RoleFunction;
public interface RoleFunctionRepository extends JpaRepository<RoleFunction, Long> {}
