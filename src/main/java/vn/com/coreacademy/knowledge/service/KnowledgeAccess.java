package vn.com.coreacademy.knowledge.service;

import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.repository.DocumentVisibility;

/** Who may see, search, share and delete knowledge documents. The rules live here, never in a prompt. */
@Component
@RequiredArgsConstructor
public class KnowledgeAccess {
    private static final Set<String> ADMIN_ROLES = Set.of(RoleCodes.ADMIN, RoleCodes.SUPER_ADMIN);

    private final LearningIdentityService identity;

    /** The documents {@code userId} may find; also checks the account is active and past the temporary password. */
    public DocumentVisibility visibility(String userId) {
        Actor actor = identity.actor(userId);
        Long orgId = actor.person().orgId();

        return new DocumentVisibility(userId, orgId == null ? Set.of() : identity.activeAncestorIds(orgId));
    }

    /** Only ADMIN and SUPER_ADMIN may share a document with everyone. */
    public boolean mayShareGlobally(Actor actor) {
        return actor.grants().stream().anyMatch(grant -> ADMIN_ROLES.contains(grant.roleCode()));
    }

    /**
     * The owner may always delete. An admin may delete a global document if their grant is global, or a unit
     * document if its unit lies inside their grant. A personal document stays with its owner.
     */
    public boolean mayDelete(Actor actor, KnowledgeDocument document) {
        if (actor.person().id().equals(document.getUploadedBy())) {
            return true;
        }
        if (document.getScope() == DocumentScope.PERSONAL) {
            return false;
        }

        for (RoleGrant grant : actor.grants()) {
            if (!ADMIN_ROLES.contains(grant.roleCode())) {
                continue;
            }
            ScopeType type = grant.scope().type();
            if (type == ScopeType.GLOBAL) {
                return true;
            }
            if (type == ScopeType.ORG && document.getScope() == DocumentScope.UNIT
                && identity.activeOrgIds(grant.scope().orgId(), true).contains(document.getScopeOrgId())) {
                return true;
            }
        }

        return false;
    }
}
