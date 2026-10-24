package vn.com.coreacademy.assistant.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.tool.AgentTool;

/** Every {@link AgentTool} bean; a new tool is one more bean, nothing here changes. */
@Component
public class ToolRegistry {
    private final List<AgentTool> tools;

    public ToolRegistry(ObjectProvider<AgentTool> tools) {
        this.tools = tools.orderedStream().toList();
    }

    /** The tools this user may be offered, in a stable order. */
    public List<AgentTool> availableTo(AgentContext context) {
        return tools.stream().filter(tool -> tool.availableTo(context)).toList();
    }

    public Optional<AgentTool> find(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
    }
}
