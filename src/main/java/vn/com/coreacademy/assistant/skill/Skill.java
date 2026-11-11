package vn.com.coreacademy.assistant.skill;

import java.util.List;

import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.identity.dto.RoleGrant;

/**
 * One assistant skill, read from {@code assistant/skills/<id>/}: the tools it may use, the roles allowed to use it
 * (empty: every role) and the prompt text that is added to the base style.
 */
public record Skill(String id, String description, List<String> tools, List<String> roles, String system,
                    String examples, String output) {
    /** A skill that reads the open lesson is only offered when the app opened the assistant from a lesson. */
    public boolean needsLesson() {
        return tools.contains("read_lesson");
    }

    public boolean allowedFor(AgentContext context) {
        if (roles.isEmpty()) {
            return true;
        }
        List<RoleGrant> granted = context.rights() == null || context.rights().roles() == null
            ? List.of() : context.rights().roles();

        return granted.stream().anyMatch(grant -> roles.contains(grant.roleCode()));
    }
}
