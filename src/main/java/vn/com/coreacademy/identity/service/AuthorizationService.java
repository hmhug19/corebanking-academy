package vn.com.coreacademy.identity.service;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.dto.Scope;
import vn.com.coreacademy.identity.dto.UserRights;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.repository.PermissionRepository;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;

import java.util.*;

@Service
@Transactional(readOnly = true)
public class AuthorizationService {
    private final RoleAssignmentRepository assignments;
    private final PermissionRepository permissions;

    public AuthorizationService(RoleAssignmentRepository assignments, PermissionRepository permissions) {
        this.assignments = assignments;
        this.permissions = permissions;
    }

    public UserRights rights(String userId) {
        List<RoleGrant> roles = new ArrayList<>();
        Set<String> effective = new TreeSet<>();
        Map<String, List<Scope>> scoped = new TreeMap<>();
        for (RoleAssignment assignment : assignments.findActiveForUser(userId)) {
            Scope scope = new Scope(assignment.getScopeType(), assignment.getOrgId(), assignment.getTeamId());
            roles.add(new RoleGrant(assignment.getRole().getCode(), scope));
            for (String code : permissions.findCodesForRole(assignment.getRole().getId())) {
                effective.add(code);
                scoped.computeIfAbsent(code, key -> new ArrayList<>()).add(scope);
            }
        }
        return new UserRights(roles, effective, scoped);
    }

    // Permission and scope must belong to the same role assignment.
    public void require(String userId, String permission, Long organizationId) {
        require(userId, permission, organizationId, null, null);
    }

    public void require(String userId, String permission, Long organizationId, Long teamId, String ownerId) {
        for (RoleAssignment assignment : assignments.findActiveForUser(userId)) {
            if (!permissions.findCodesForRole(assignment.getRole().getId()).contains(permission)) continue;
            if (assignment.getScopeType() == ScopeType.GLOBAL) return;
            // Shared catalog changes cannot be authorized by an ORG/TEAM assignment.
            if (Set.of("role.manage", "position.manage").contains(permission)) continue;
            if (assignment.getScopeType() == ScopeType.ORG
                    && organizationId != null && organizationId.equals(assignment.getOrgId())) return;
            if (assignment.getScopeType() == ScopeType.TEAM
                    && organizationId != null && organizationId.equals(assignment.getOrgId())
                    && teamId != null && teamId.equals(assignment.getTeamId())) return;
            if (assignment.getScopeType() == ScopeType.OWN && userId.equals(ownerId)) return;
        }
        throw new AccessDeniedException("Permission or organization scope denied");
    }
}
