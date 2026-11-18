package vn.com.coreacademy.assistant.skill;

import java.util.Collection;
import java.util.List;

import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.identity.dto.RoleGrant;

/**
 * One assistant skill, read from {@code assistant/skills/<id>/}: the tools it may use, the roles allowed to use it
 * (empty: every role) and the prompt text that is added to the base style. {@code requiredTools} (skill.yml
 * {@code requiredTool: x} or {@code requiredTools: [x, y]}, may be empty) are the tools of which a turn must call at
 * least one before the model may answer in its own words.
 */
public record Skill(String id, String description, List<String> tools, List<String> roles, String system,
                    String examples, String output, List<String> requiredTools) {
    public Skill {
        requiredTools = requiredTools == null ? List.of() : List.copyOf(requiredTools);
    }

    /** A skill with no tool it must call. */
    public Skill(String id, String description, List<String> tools, List<String> roles, String system, String examples,
                 String output) {
        this(id, description, tools, roles, system, examples, output, List.of());
    }

    /** A skill that must call one tool; {@code requiredTool} may be null for none. */
    public Skill(String id, String description, List<String> tools, List<String> roles, String system, String examples,
                 String output, String requiredTool) {
        this(id, description, tools, roles, system, examples, output,
            requiredTool == null ? List.of() : List.of(requiredTool));
    }

    /** The required tools that are actually offered this turn, in declared order. */
    public List<String> requiredAmong(Collection<String> offered) {
        return requiredTools.stream().filter(offered::contains).toList();
    }

    /** True when a turn of this skill must call at least one of its {@link #requiredTools()} offered in {@code offered}. */
    public boolean mustCall(Collection<String> offered) {
        return !requiredAmong(offered).isEmpty();
    }

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
