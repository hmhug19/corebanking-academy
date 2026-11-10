package vn.com.coreacademy.assistant.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.identity.service.AuthService;

/** The one place an AI endpoint asks "may this caller use the assistant?", so no endpoint can forget the check. */
@Component
@RequiredArgsConstructor
public class AssistantGate {
    private final AuthService auth;

    /** Throws AccessDeniedException (403) unless the caller holds the {@code AI_ASSISTANT} function. */
    public void requireAccess() {
        AssistantAccess.require(auth.me());
    }
}
