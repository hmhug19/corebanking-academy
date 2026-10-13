package vn.com.coreacademy.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.organization.entity.Position;

public interface PositionRepository extends JpaRepository<Position, Long> {
}
