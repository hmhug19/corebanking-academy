package vn.com.coreacademy.assistant.agent;

import org.springframework.security.access.AccessDeniedException;
import vn.com.coreacademy.identity.dto.MeResponse;

/**
 * The assistant is the {@code AI_ASSISTANT} function, granted to every seeded role. A user still on a temporary
 * password has no functions at all, so this one check also keeps them out until the password is changed.
 */
public final class AssistantAccess {
    public static final String FUNCTION = "AI_ASSISTANT";

    private AssistantAccess() {
    }

    public static MeResponse require(MeResponse me) {
        if (!me.functions().contains(FUNCTION)) {
            throw new AccessDeniedException("The assistant function is not granted");
        }

        return me;
    }
}
