package vn.com.coreacademy.organization.dto;

public record OrganizationInfo(Long id, Long parentId, String orgType, String code, String name,
                               String status, int sortNo, long memberCount, long teamCount) {
}
