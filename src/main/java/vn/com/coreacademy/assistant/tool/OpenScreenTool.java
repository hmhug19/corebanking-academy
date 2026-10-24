package vn.com.coreacademy.assistant.tool;

import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.tool.ToolDefinitions.Screen;
import vn.com.coreacademy.identity.dto.FunctionInfo;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;

/**
 * Opens one of the user's own screens. The model may only name a function code from the list built here from the
 * user's grants (the same source as the sidebar); the path always comes from the server, never from the model.
 */
@Component
public class OpenScreenTool implements AgentTool {
    private static final String NOT_ALLOWED = "Bạn không có quyền mở màn hình này hoặc màn hình không tồn tại.";

    @Override
    public boolean actsForUser() {
        return true;
    }

    @Override
    public String name() {
        return ToolDefinitions.OPEN_SCREEN;
    }

    @Override
    public boolean availableTo(AgentContext context) {
        return !openable(context).isEmpty();
    }

    @Override
    public ToolDefinition definition(AgentContext context) {
        List<Screen> screens = openable(context).stream()
            .map(function -> new Screen(function.code(), function.name())).toList();
        return ToolDefinitions.openScreen(screens);
    }

    @Override
    public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
        // Ollama does not enforce the schema's enum, so the code is checked here.
        String code = ToolArguments.text(arguments, "functionCode");
        return openable(context).stream()
            .filter(function -> function.code().equals(code))
            .findFirst()
            .<ToolOutcome>map(function -> new ToolOutcome.Navigate(function.routePattern(), function.name(),
                "Đã mở trang " + function.name() + "."))
            .orElseGet(() -> new ToolOutcome.Result(NOT_ALLOWED));
    }

    /** Granted functions with a fixed route; a route with a {@code :parameter} needs context the model cannot give. */
    private static List<FunctionInfo> openable(AgentContext context) {
        return context.rights().functions().stream()
            .filter(function -> function.routePattern() != null && !function.routePattern().isBlank())
            .filter(function -> !function.routePattern().contains(":"))
            .sorted(Comparator.comparingInt(FunctionInfo::sortNo).thenComparing(FunctionInfo::id))
            .toList();
    }
}
