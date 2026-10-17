package vn.com.coreacademy.organization.repository.custom;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.com.coreacademy.organization.entity.Organization;

public interface OrganizationRepositoryCustom {
    record OrganizationSearch(String q, Long parentId, boolean rootOnly, String orgType, String status,
                              boolean global, Set<Long> visibleOrgIds) {
        public OrganizationSearch {
            visibleOrgIds = Set.copyOf(visibleOrgIds);
        }
    }

    Page<Organization> searchOrganizations(OrganizationSearch filter, Pageable pageable);
}
