package vn.com.coreacademy.assistant.agent;

import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.dto.UserRights;

/**
 * Who is asking, captured on the request thread before the stream starts. The stream body runs on another thread
 * without the security context, so tools must take identity from here.
 */
public record AgentContext(String userId, UserInfo profile, UserRights rights) {
}
