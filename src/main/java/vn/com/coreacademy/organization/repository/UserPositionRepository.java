package vn.com.coreacademy.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.organization.entity.UserPosition;

public interface UserPositionRepository extends JpaRepository<UserPosition, Long> {
}
