package vn.com.coreacademy.assistant.action;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.agent.AssistantAccess;
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
    private static final String GENERIC_FAILURE = "Không thực hiện được thay đổi này.";

    private final PendingActionStore store;
    private final ToolRegistry registry;
    private final AuthService auth;

    public ActionResultView confirm(String actionId) {
        AgentContext context = AgentContext.of(AssistantAccess.require(auth.me()));
        PendingAction action = store.take(actionId, SecurityContextUtils.getUserId());
        var tool = registry.find(action.tool()).orElse(null);
        if (tool == null) {
            store.failed(action.id(), AssistantResponseCode.ACTION_NOT_FOUND.getMessage());
            throw new BusinessException(AssistantResponseCode.ACTION_NOT_FOUND);
        }

        ActionResultView result;
        try {
            result = tool.execute(action, context);
        } catch (RuntimeException exception) {
            // The action was taken either way; its row keeps the reason so a reopened conversation shows it.
            store.failed(action.id(), exception instanceof BusinessException business
                ? business.getResponseCode().getMessage() : GENERIC_FAILURE);
            throw exception;
        }
        store.succeeded(action.id(), result.message());

        return result;
    }

    public void cancel(String actionId) {
        AssistantAccess.require(auth.me());
        store.cancel(actionId, SecurityContextUtils.getUserId());
    }
}
