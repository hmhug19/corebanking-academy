package vn.com.coreacademy.assistant.agent;

import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.dto.UserRights;

/**
 * Who is asking, captured on the request thread before the stream starts. The stream body runs on another thread
 * without the security context, so tools must take identity from here. {@code conversationId} is the conversation the
 * turn is stored in, set once the stream has opened it.
 */
public record AgentContext(String userId, UserInfo profile, UserRights rights, LessonFocus lesson,
                           Long attachedDocumentId, Long conversationId) {
    public AgentContext(String userId, UserInfo profile, UserRights rights) {
        this(userId, profile, rights, null, null, null);
    }

    public AgentContext(String userId, UserInfo profile, UserRights rights, LessonFocus lesson,
                        Long attachedDocumentId) {
        this(userId, profile, rights, lesson, attachedDocumentId, null);
    }

    public static AgentContext of(MeResponse me) {
        UserRights rights = new UserRights(me.roleGrants(), me.functionDetails(), me.functionScopes());
        return new AgentContext(me.user().userId(), me.user(), rights, null, null, null);
    }

    /** The same user asking from a lesson page; the lesson was already checked against their access. */
    public AgentContext withLesson(LessonFocus focus) {
        return new AgentContext(userId, profile, rights, focus, attachedDocumentId, conversationId);
    }

    /**
     * A file the user attached in the composer, already uploaded as their own course source. The id is a claim: a tool
     * that uses it checks it belongs to {@code userId}.
     */
    public AgentContext withAttachment(Long documentId) {
        return new AgentContext(userId, profile, rights, lesson, documentId, conversationId);
    }

    /** The conversation this turn is stored in; it is the caller's (the stream opened or checked it). */
    public AgentContext withConversation(Long id) {
        return new AgentContext(userId, profile, rights, lesson, attachedDocumentId, id);
    }
}
