package vn.com.coreacademy.identity.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import vn.com.coreacademy.identity.entity.Role;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.repository.PermissionRepository;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorizationServiceTest {
    private final RoleAssignmentRepository assignments = mock(RoleAssignmentRepository.class);
    private final PermissionRepository permissions = mock(PermissionRepository.class);
    private final AuthorizationService service = new AuthorizationService(assignments, permissions);

    @Test
    void rejectsUnknownPermission() {
        when(assignments.findActiveForUser("183617")).thenReturn(List.of());
        assertThrows(AccessDeniedException.class, () -> service.require("183617", "course.update", 2L));
    }

    @Test
    void matchingRoleButWrongOrganizationIsDenied() {
        when(assignments.findActiveForUser("183617")).thenReturn(List.of(grant(3L, ScopeType.ORG, 5L)));
        when(permissions.findCodesForRole(3L)).thenReturn(List.of("course.update"));
        assertThrows(AccessDeniedException.class, () -> service.require("183617", "course.update", 6L));
        assertDoesNotThrow(() -> service.require("183617", "course.update", 5L));
    }

    @Test
    void doesNotCombinePermissionAndScopeFromDifferentAssignments() {
        when(assignments.findActiveForUser("183617")).thenReturn(List.of(
                grant(3L, ScopeType.ORG, 5L), grant(4L, ScopeType.GLOBAL, null)));
        when(permissions.findCodesForRole(3L)).thenReturn(List.of("course.update"));
        when(permissions.findCodesForRole(4L)).thenReturn(List.of("report.view"));
        assertThrows(AccessDeniedException.class, () -> service.require("183617", "course.update", 6L));
    }

    @Test
    void globalGrantAllowsPermissionAcrossUnits() {
        when(assignments.findActiveForUser("183617")).thenReturn(List.of(grant(3L, ScopeType.GLOBAL, null)));
        when(permissions.findCodesForRole(3L)).thenReturn(List.of("course.update"));
        assertDoesNotThrow(() -> service.require("183617", "course.update", 6L));
    }

    @Test
    void teamScopeRequiresBothOrganizationAndTeam() {
        RoleAssignment assignment = grant(3L, ScopeType.TEAM, 5L);
        assignment.setTeamId(9L);
        when(assignments.findActiveForUser("183617")).thenReturn(List.of(assignment));
        when(permissions.findCodesForRole(3L)).thenReturn(List.of("user.view"));
        assertDoesNotThrow(() -> service.require("183617", "user.view", 5L, 9L, null));
        assertThrows(AccessDeniedException.class, () -> service.require("183617", "user.view", 6L, 9L, null));
        assertThrows(AccessDeniedException.class, () -> service.require("183617", "user.view", 5L, 10L, null));
        assertThrows(AccessDeniedException.class, () -> service.require("183617", "user.view", 5L));
    }

    @Test
    void ownScopeRequiresActualResourceOwner() {
        when(assignments.findActiveForUser("183617")).thenReturn(List.of(grant(3L, ScopeType.OWN, null)));
        when(permissions.findCodesForRole(3L)).thenReturn(List.of("profile.view"));
        assertDoesNotThrow(() -> service.require("183617", "profile.view", null, null, "183617"));
        assertThrows(AccessDeniedException.class, () -> service.require("183617", "profile.view", null, null, "other"));
        assertThrows(AccessDeniedException.class, () -> service.require("183617", "profile.view", null));
    }

    @Test
    void organizationAdminCannotChangeSharedRoleCatalog() {
        when(assignments.findActiveForUser("183617")).thenReturn(List.of(grant(3L, ScopeType.ORG, 5L)));
        when(permissions.findCodesForRole(3L)).thenReturn(List.of("role.manage"));
        assertThrows(AccessDeniedException.class, () -> service.require("183617", "role.manage", 5L));
    }

    private RoleAssignment grant(Long roleId, ScopeType scope, Long scopeId) {
        Role role = new Role();
        role.setId(roleId);
        role.setCode("ROLE_" + roleId);
        RoleAssignment assignment = new RoleAssignment();
        assignment.setRole(role);
        assignment.setScopeType(scope);
        assignment.setOrgId(scopeId);
        return assignment;
    }
}
