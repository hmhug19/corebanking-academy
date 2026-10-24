package vn.com.coreacademy.assistant.tool;

import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.assistant.action.PendingAction;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.dto.ActionResultView;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;

/** A function the model may call. A tool reads identity only from the {@link AgentContext}, never the thread. */
public interface AgentTool {
    String name();

    /** Whether this user may be offered the tool at all. */
    boolean availableTo(AgentContext context);

    /** What the model is told; a tool may tailor it to the user (for example the user's own screens). */
    ToolDefinition definition(AgentContext context);

    /** Validates the model's arguments and either answers, navigates or proposes an action. */
    ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn);

    /** Only tools that change data; runs after the user confirms, on the request thread. */
    default ActionResultView execute(PendingAction action, AgentContext context) {
        throw new UnsupportedOperationException(name() + " changes no data");
    }
}
