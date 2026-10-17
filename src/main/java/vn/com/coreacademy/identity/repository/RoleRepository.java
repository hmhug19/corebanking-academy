package vn.com.coreacademy.identity.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.identity.entity.Role;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByCode(String code);
}
