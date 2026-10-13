package vn.com.coreacademy.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.organization.entity.UserTeam;
import vn.com.coreacademy.organization.entity.UserTeamId;

public interface UserTeamRepository extends JpaRepository<UserTeam, UserTeamId> {
}
