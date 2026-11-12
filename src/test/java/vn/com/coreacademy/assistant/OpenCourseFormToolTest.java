package vn.com.coreacademy.assistant;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.tool.OpenCourseFormTool;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.dto.UserRights;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCourseFormToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OpenCourseFormTool tool = new OpenCourseFormTool();

    private static AgentContext as(String role) {
        return new AgentContext("000004", null, new UserRights(List.of(new RoleGrant(role, role, null)), List.of(), Map.of()));
    }

    private ToolOutcome.Navigate run(ObjectNode arguments) {
        return (ToolOutcome.Navigate) tool.run(arguments, as("INSTRUCTOR"), new TurnState());
    }

    @Test
    void whatTheUserSaidBecomesThePrefillAndThePathIsFixed() {
        ToolOutcome.Navigate outcome = run(JSON.createObjectNode().put("title", " [TEST-AI] Khóa thử ")
            .put("targetLearners", "Giao dịch viên mới").put("level", "BEGINNER").put("chapters", 3)
            .put("durationMinutes", 120));

        assertThat(outcome.path()).isEqualTo("/admin/courses/new");
        assertThat(outcome.prefill()).containsEntry("title", "[TEST-AI] Khóa thử")
            .containsEntry("targetLearners", "Giao dịch viên mới").containsEntry("level", "BEGINNER")
            .containsEntry("chapters", 3).containsEntry("durationMinutes", 120);
    }

    @Test
    void noArgumentsOpensTheFormEmptyAndOutOfRangeValuesAreDropped() {
        assertThat(run(JSON.createObjectNode()).prefill()).isEmpty();
        assertThat(run(JSON.createObjectNode().put("level", "EXPERT").put("chapters", 99).put("durationMinutes", 1)
            .put("title", "")).prefill()).isEmpty();
    }

    @Test
    void onlyInstructorsAndAdminsHaveItAndALearnerIsRefused() {
        assertThat(tool.availableTo(as("LEARNER"))).isFalse();
        assertThat(tool.availableTo(as("INSTRUCTOR"))).isTrue();
        assertThat(tool.availableTo(as("ADMIN"))).isTrue();
        assertThat(tool.run(JSON.createObjectNode(), as("LEARNER"), new TurnState())).isInstanceOf(ToolOutcome.Result.class);
    }

    @Test
    void theDefinitionNeedsNoArgumentAndNamesNoLookup() {
        var definition = tool.definition(as("INSTRUCTOR"));

        assertThat(definition.name()).isEqualTo("open_course_form");
        assertThat(definition.parameters().path("required").isMissingNode()).isTrue();
        assertThat(definition.parameters().path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("title", "targetLearners", "level", "chapters", "durationMinutes");
    }
}
