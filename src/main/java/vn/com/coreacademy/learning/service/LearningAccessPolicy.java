package vn.com.coreacademy.learning.service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.TrainingProgram;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

@Component
@RequiredArgsConstructor
public class LearningAccessPolicy {
    private final LearningIdentityService identity;
    private final LearningRepository repository;

    public Actor actor() {
        return identity.actor(SecurityContextUtils.getUserId());
    }

    public record SearchScope(boolean global, Set<Long> orgIds, Set<Long> teamIds, boolean own) {
    }

    public void requireStaff(Actor actor) {
        boolean staff = actor.grants().stream().anyMatch(grant ->
            (RoleCodes.SUPER_ADMIN.equals(grant.roleCode()) && grant.scope().type() == ScopeType.GLOBAL)
                || Set.of(RoleCodes.ADMIN, RoleCodes.INSTRUCTOR).contains(grant.roleCode()));
        if (!staff) {
            throw new AccessDeniedException("Staff role required");
        }
    }

    public SearchScope searchScope(Actor actor) {
        boolean global = false;
        boolean own = false;
        Set<Long> orgs = new HashSet<>();
        Set<Long> teams = new HashSet<>();
        for (var grant : actor.grants()) {
            if (!Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN, RoleCodes.INSTRUCTOR).contains(grant.roleCode())) {
                continue;
            }
            switch (grant.scope().type()) {
                case GLOBAL -> global = true;
                case OWN -> own = true;
                case ORG -> orgs.addAll(identity.activeOrgIds(grant.scope().orgId(), true));
                case TEAM -> teams.add(grant.scope().teamId());
            }
        }
        return new SearchScope(global, Set.copyOf(orgs), Set.copyOf(teams), own);
    }

    /** Grant đã được đối chiếu với một đơn vị; chỉ dùng để lọc dữ liệu thuộc chính đơn vị đó. */
    public record CatalogPermission(boolean orgWide, Set<Long> teams, String ownerId) {
        public boolean includesOwner(String createdBy) {
            return orgWide || (ownerId != null && ownerId.equals(createdBy));
        }

        public boolean includesCourse(Course course) {
            return includesOwner(course.getOwnerUserId())
                || (course.getOwnerTeamId() != null && teams.contains(course.getOwnerTeamId()));
        }
    }

    public CatalogPermission catalogPermission(Actor actor, Long orgId) {
        requireStaff(actor);
        SearchScope scope = searchScope(actor);
        if (scope.global() || scope.orgIds().contains(orgId)) {
            identity.activeOrg(orgId);
            return new CatalogPermission(true, Set.of(), null);
        }
        Set<Long> teams = new HashSet<>();
        for (Long teamId : scope.teamIds()) {
            if (Objects.equals(identity.activeTeam(teamId).orgId(), orgId)) {
                teams.add(teamId);
            }
        }
        String owner = scope.own() && Objects.equals(actor.person().orgId(), orgId) ? actor.person().id() : null;
        if (teams.isEmpty() && owner == null) {
            throw new AccessDeniedException("Catalog scope denied");
        }
        identity.activeOrg(orgId);
        return new CatalogPermission(false, Set.copyOf(teams), owner);
    }

    public Set<Long> scopedProgramIds(Long orgId, CatalogPermission permission) {
        return Set.copyOf(repository.scopedProgramIds(orgId, permission.teams(), permission.ownerId()));
    }

    public void requireUsableProgram(Actor actor, TrainingProgram program) {
        Long orgId = program.getOwnerOrgId();
        CatalogPermission permission = catalogPermission(actor, orgId);
        if (!permission.includesOwner(program.getCreatedBy())
            && !scopedProgramIds(orgId, permission).contains(program.getId())) {
            throw new AccessDeniedException("Training program scope denied");
        }
    }

    /** Role và scope luôn xét trên cùng grant; chủ sở hữu vẫn phải có quyền giảng viên/quản trị hiện tại. */
    public boolean canManage(Actor actor, Long orgId, Long teamId, String ownerId) {
        for (RoleGrant grant : actor.grants()) {
            String role = grant.roleCode();
            if (RoleCodes.SUPER_ADMIN.equals(role) && grant.scope().type() == ScopeType.GLOBAL) {
                return true;
            }
            if (!Set.of(RoleCodes.ADMIN, RoleCodes.INSTRUCTOR).contains(role)) {
                continue;
            }
            switch (grant.scope().type()) {
                case GLOBAL -> {
                    return true;
                }
                case ORG -> {
                    if (orgId != null && identity.activeOrgIds(grant.scope().orgId(), true).contains(orgId)) {
                        return true;
                    }
                }
                case TEAM -> {
                    if (teamId != null && teamId.equals(grant.scope().teamId())
                        && Objects.equals(identity.activeTeam(teamId).orgId(), orgId)) {
                        return true;
                    }
                }
                case OWN -> {
                    if (actor.person().id().equals(ownerId) && Objects.equals(actor.person().orgId(), orgId)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public void requireManage(Actor actor, Course course) {
        requireManage(actor, course.getOwnerOrgId(), course.getOwnerTeamId(), course.getOwnerUserId());
    }

    public void requireManage(Actor actor, Long orgId, Long teamId, String ownerId) {
        identity.activeOrg(orgId);
        if (!canManage(actor, orgId, teamId, ownerId)) {
            throw new AccessDeniedException("Learning scope denied");
        }
    }

    /** OWN chỉ quản lý khóa do mình tạo, không được dùng OWN để giao cho mọi học viên cùng đơn vị. */
    public void requireRecipientScope(Actor actor, LearningIdentityService.Person person) {
        requireManage(actor, person.orgId(), person.teamId(), null);
    }

    public void requireLearner(Actor actor) {
        if (!isLearner(actor)) {
            throw new AccessDeniedException("Learner role required");
        }
    }

    /** Có vai trò học viên (LEARNER, phạm vi OWN). */
    public boolean isLearner(Actor actor) {
        return actor.grants().stream().anyMatch(g -> RoleCodes.LEARNER.equals(g.roleCode())
            && g.scope().type() == ScopeType.OWN);
    }
}
