package vn.com.coreacademy.organization.dto;

import java.util.List;

public record OrganizationDetail(OrganizationInfo organization, List<Administrator> administrators) {
    public record Administrator(String userId, String fullName, String roleCode, Long grantOrgId) {
    }
}
