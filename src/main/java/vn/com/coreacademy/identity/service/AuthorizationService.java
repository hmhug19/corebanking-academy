package vn.com.coreacademy.identity.service;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.identity.dto.*;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.repository.FunctionRepository;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.organization.repository.TeamRepository;
import java.util.*;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthorizationService {
    private final RoleAssignmentRepository assignments;
    private final FunctionRepository functions;
    private final TeamRepository teams;

    public UserRights rights(String userId) {
        List<RoleGrant> roles = new ArrayList<>();
        Map<Long, FunctionInfo> screens = new LinkedHashMap<>();
        Map<String, List<Scope>> scoped = new TreeMap<>();
        for (RoleAssignment grant : assignments.findActiveForUser(userId)) {
            Scope scope = new Scope(grant.getScopeType(), grant.getOrgId(), grant.getTeamId());
            roles.add(new RoleGrant(grant.getRole().getCode(), grant.getRole().getName(), scope));
            for (var f : functions.findActiveForRole(grant.getRole().getId())) {
                screens.putIfAbsent(f.getId(), new FunctionInfo(f.getId(), f.getCode(), f.getName(), f.getRoutePattern(), f.getParentFunctionId(), f.getSortNo()));
                scoped.computeIfAbsent(f.getCode(), key -> new ArrayList<>()).add(scope);
            }
        }
        List<FunctionInfo> ordered = screens.values().stream()
                .sorted(Comparator.comparingInt(FunctionInfo::sortNo).thenComparing(FunctionInfo::id)).toList();
        return new UserRights(roles, ordered, scoped);
    }
    // API actions must explicitly choose the required role; screen access never authorizes writes.
    // Role and scope must match on the same assignment. No implicit SUPER_ADMIN bypass.
    public void requireRole(String userId, String role, Long orgId, Long teamId, String ownerId) {
        for (RoleAssignment grant : assignments.findActiveForUser(userId)) {
            if (!grant.getRole().getCode().equals(role)) continue;
            ScopeType scope = grant.getScopeType();
            if (scope == ScopeType.GLOBAL && grant.getOrgId() == null && grant.getTeamId() == null) return;
            if (scope == ScopeType.ORG && orgId != null && orgId.equals(grant.getOrgId()) && grant.getTeamId() == null) return;
            if (scope == ScopeType.TEAM && teamId != null && teamId.equals(grant.getTeamId()) && orgId != null) {
                var team = teams.findById(teamId).orElse(null);
                if (team != null && "active".equals(team.getStatus()) && orgId.equals(team.getOrgId())
                        && (grant.getOrgId() == null || orgId.equals(grant.getOrgId()))) return;
            }
            if (scope == ScopeType.OWN && grant.getOrgId() == null && grant.getTeamId() == null && userId.equals(ownerId)) return;
        }
        throw new AccessDeniedException("Role or scope denied");
    }
}
