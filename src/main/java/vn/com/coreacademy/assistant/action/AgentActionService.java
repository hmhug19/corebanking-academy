package vn.com.coreacademy.assistant.action;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.agent.ToolRegistry;
import vn.com.coreacademy.assistant.dto.ActionResultView;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.AuthService;
import vn.com.coreacademy.security.SecurityContextUtils;

/**
 * Answers a pending action. Confirming takes the action out of the store first, so a double click runs once, then
 * runs the tool on the request thread with the caller's own security context: the tool's service applies the same
 * checks as the screen the action stands in for. An action taken here is gone even if the tool then refuses.
 */
@Service
@RequiredArgsConstructor
public class AgentActionService {
    private final PendingActionStore store;
    private final ToolRegistry registry;
    private final AuthService auth;

    public ActionResultView confirm(String actionId) {
        PendingAction action = store.take(actionId, SecurityContextUtils.getUserId());
        var tool = registry.find(action.tool())
            .orElseThrow(() -> new BusinessException(AssistantResponseCode.ACTION_NOT_FOUND));

        return tool.execute(action, AgentContext.of(auth.me()));
    }

    public void cancel(String actionId) {
        store.cancel(actionId, SecurityContextUtils.getUserId());
    }
}
