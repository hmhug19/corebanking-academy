package vn.com.coreacademy.identity.dto;

import java.time.Instant;

public record UserInfo(String userId, String username, String fullName, String email,
                       String status, String authProvider, boolean mustChangePassword,
                       Long orgId, String orgCode, String orgName, String orgType,
                       Long teamId, String teamCode, String teamName, String positionName,
                       String brn, String brcd, String cra, String brnName, String brcdName,
                       String craName, String deptCode, String deptName, String bdsCode, String bdsName,
                       String qlnsBrnCode, Instant lastSyncTime) {
}
