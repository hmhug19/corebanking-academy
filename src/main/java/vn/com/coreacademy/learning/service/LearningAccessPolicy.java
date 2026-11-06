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
import vn.com.coreacademy.security.SecurityContextUtils;

@Component
@RequiredArgsConstructor
public class LearningAccessPolicy {
    private final LearningIdentityService identity;

    public Actor actor() {
        return identity.actor(SecurityContextUtils.getUserId());
    }

    public record SearchScope(boolean global, Set<Long> orgIds, Set<Long> teamIds, boolean own) {
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
