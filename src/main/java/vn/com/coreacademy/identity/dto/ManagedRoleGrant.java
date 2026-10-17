package vn.com.coreacademy.identity.dto;

import java.time.Instant;

public record ManagedRoleGrant(Long grantId, Long roleId, String roleCode, String roleName,
                               Scope scope, String orgName, String teamName, String grantedBy,
                               Instant grantedAt, boolean revocable) {
}
