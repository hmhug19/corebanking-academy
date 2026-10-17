package vn.com.coreacademy.identity.dto;

import java.util.List;

public record UserSummary(String userId, String username, String fullName, String email, String status,
                          String authProvider, boolean mustChangePassword, Long orgId, String orgName,
                          String orgType, Long teamId, String teamName, String positionName,
                          List<String> roleCodes, boolean editable) {
}
