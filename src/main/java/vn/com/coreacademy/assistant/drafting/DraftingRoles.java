package vn.com.coreacademy.assistant.drafting;

import org.springframework.security.access.AccessDeniedException;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

/**
 * The roles that may use Soạn cùng trợ lý: those that manage courses. The drafting endpoints, the Kết nối trợ lý
 * ngoài endpoints and the MCP endpoint all ask here, so the set has one source.
 */
public final class DraftingRoles {
    private DraftingRoles() {
    }

    public static boolean mayDraft(Actor actor) {
        return actor.grants().stream().anyMatch(grant -> RoleCodes.COURSE_MANAGERS.contains(grant.roleCode()));
    }

    /** Throws AccessDeniedException (403) unless the actor holds a role that manages courses. */
    public static void require(Actor actor) {
        if (!mayDraft(actor)) {
            throw new AccessDeniedException("Only roles that manage courses may draft a course with the assistant");
        }
    }
}
