package vn.com.coreacademy.assistant.agent;

import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.dto.UserRights;

/**
 * Who is asking, captured on the request thread before the stream starts. The stream body runs on another thread
 * without the security context, so tools must take identity from here.
 */
public record AgentContext(String userId, UserInfo profile, UserRights rights) {
    public static AgentContext of(MeResponse me) {
        UserRights rights = new UserRights(me.roleGrants(), me.functionDetails(), me.functionScopes());
        return new AgentContext(me.user().userId(), me.user(), rights);
    }
}
