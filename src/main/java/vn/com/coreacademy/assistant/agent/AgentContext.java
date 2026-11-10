package vn.com.coreacademy.assistant.agent;

import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.dto.UserRights;

/**
 * Who is asking, captured on the request thread before the stream starts. The stream body runs on another thread
 * without the security context, so tools must take identity from here.
 */
public record AgentContext(String userId, UserInfo profile, UserRights rights, LessonFocus lesson) {
    public AgentContext(String userId, UserInfo profile, UserRights rights) {
        this(userId, profile, rights, null);
    }

    public static AgentContext of(MeResponse me) {
        UserRights rights = new UserRights(me.roleGrants(), me.functionDetails(), me.functionScopes());
        return new AgentContext(me.user().userId(), me.user(), rights, null);
    }

    /** The same user asking from a lesson page; the lesson was already checked against their access. */
    public AgentContext withLesson(LessonFocus focus) {
        return new AgentContext(userId, profile, rights, focus);
    }
}
