package vn.com.coreacademy.identity.dto;



public record UserInfo(String id, String username, String fullName, String status, long authorizationVersion,
                       String email, String brn, String brcd, String cra, String brnName, String brcdName,
                       String craName, String deptCode, String deptName, String bdsCode, String bdsName,
                       String qlnsBrnCode, java.time.Instant lastSyncTime) {
}
