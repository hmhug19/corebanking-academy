package vn.com.coreacademy.organization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.organization.entity.Organization;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
}
