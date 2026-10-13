package vn.com.coreacademy.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.organization.entity.Team;

public interface TeamRepository extends JpaRepository<Team, Long> {
}
