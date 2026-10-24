package vn.com.coreacademy.organization.dto;

/** dryRun chỉ xác nhận dữ liệu tại thời điểm kiểm tra; lúc nhập thật luôn kiểm tra lại. */
public record OrganizationImportResult(boolean dryRun, int organizationCount, int teamCount,
                                        int createdOrganizations, int createdTeams) {
}
