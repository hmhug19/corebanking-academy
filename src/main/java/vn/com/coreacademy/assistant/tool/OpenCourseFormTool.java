package vn.com.coreacademy.assistant.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;

/**
 * Sends an instructor or administrator to the new-course form with what the message already said. It creates nothing:
 * the course and the job start when the user presses "Tạo bằng AI" on the form, with the source file the browser kept.
 * The path is fixed here; the model only supplies optional text and numbers, and values outside the form's own bounds
 * are dropped instead of being passed on.
 */
@Component
public class OpenCourseFormTool implements AgentTool {
    static final String PATH = "/admin/courses/new";
    private static final String TITLE = "Khóa học mới";
    private static final String ANSWER = "Mình đã mở form Khóa học mới. Bạn chọn chương trình, năng lực và bấm \"Tạo bằng AI\" nhé.";
    private static final Set<String> MANAGERS = Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN, RoleCodes.INSTRUCTOR);
    private static final Set<String> LEVELS = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");

    @Override
    public boolean actsForUser() {
        return true;
    }

    @Override
    public String name() {
        return ToolDefinitions.OPEN_COURSE_FORM;
    }

    @Override
    public boolean availableTo(AgentContext context) {
        return roles(context).anyMatch(MANAGERS::contains);
    }

    @Override
    public ToolDefinition definition(AgentContext context) {
        return ToolDefinitions.openCourseForm();
    }

    @Override
    public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
        if (!availableTo(context)) {
            return new ToolOutcome.Result("Tính năng tạo khóa học dành cho giảng viên.");
        }
        Map<String, Object> prefill = new LinkedHashMap<>();
        put(prefill, "title", text(arguments, "title", 200));
        put(prefill, "targetLearners", text(arguments, "targetLearners", 500));
        String level = ToolArguments.text(arguments, "level");
        put(prefill, "level", level != null && LEVELS.contains(level) ? level : null);
        put(prefill, "chapters", number(arguments, "chapters", 1, 20));
        put(prefill, "durationMinutes", number(arguments, "durationMinutes", 15, 1200));

        return new ToolOutcome.Navigate(PATH, TITLE, ANSWER, prefill);
    }

    private static void put(Map<String, Object> into, String key, Object value) {
        if (value != null) {
            into.put(key, value);
        }
    }

    private static String text(JsonNode arguments, String name, int max) {
        String value = ToolArguments.text(arguments, name);

        return value == null || value.isEmpty() || value.length() > max ? null : value;
    }

    private static Integer number(JsonNode arguments, String name, int min, int max) {
        if (arguments == null || !arguments.path(name).isInt()) {
            return null;
        }
        int value = arguments.path(name).asInt();

        return value < min || value > max ? null : value;
    }

    private static Stream<String> roles(AgentContext context) {
        if (context.rights() == null || context.rights().roles() == null) {
            return Stream.empty();
        }

        return context.rights().roles().stream().map(RoleGrant::roleCode);
    }
}
