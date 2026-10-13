package vn.com.coreacademy.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.organization.entity.UserOrgId;
import vn.com.coreacademy.organization.entity.UserOrganization;

public interface UserOrganizationRepository extends JpaRepository<UserOrganization, UserOrgId> {
}
