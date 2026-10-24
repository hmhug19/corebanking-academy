package vn.com.coreacademy.assistant;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.tool.OpenScreenTool;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.identity.dto.FunctionInfo;
import vn.com.coreacademy.identity.dto.UserRights;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class OpenScreenToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NOT_ALLOWED = "Bạn không có quyền mở màn hình này hoặc màn hình không tồn tại.";

    private final OpenScreenTool tool = new OpenScreenTool();

    private static FunctionInfo function(long id, String code, String name, String route, int sortNo) {
        return new FunctionInfo(id, code, name, route, null, sortNo);
    }

    private static AgentContext userWith(FunctionInfo... functions) {
        return new AgentContext("000004", null, new UserRights(List.of(), List.of(functions), Map.of()));
    }

    private ToolOutcome open(AgentContext context, String code) {
        return tool.run(JSON.createObjectNode().put("functionCode", code), context, new TurnState());
    }

    private static List<String> enumOf(ToolDefinition definition) {
        JsonNode codes = definition.parameters().path("properties").path("functionCode").path("enum");
        return StreamSupport.stream(codes.spliterator(), false).map(JsonNode::asText).toList();
    }

    @Test
    void theEnumAndDescriptionListOnlyTheUsersOwnStaticRoutesInSortOrder() {
        AgentContext learner = userWith(
            function(3, "LEARNING_PROGRESS", "Tiến độ học tập", "/progress", 30),
            function(1, "PROFILE", "Hồ sơ cá nhân", "/profile", 10),
            function(7, "COURSE_DETAIL", "Chi tiết khóa học", "/learn/:courseId", 25),
            function(8, "NO_ROUTE", "Không có route", null, 5),
            function(9, "BLANK_ROUTE", "Route trống", " ", 6));

        ToolDefinition definition = tool.definition(learner);

        assertThat(enumOf(definition)).containsExactly("PROFILE", "LEARNING_PROGRESS");
        assertThat(definition.name()).isEqualTo("open_screen");
        assertThat(definition.description()).contains("PROFILE (Hồ sơ cá nhân)")
            .contains("LEARNING_PROGRESS (Tiến độ học tập)")
            .doesNotContain("COURSE_DETAIL").doesNotContain("NO_ROUTE").doesNotContain("BLANK_ROUTE");
        assertThat(definition.parameters().path("required").get(0).asText()).isEqualTo("functionCode");
    }

    @Test
    void aGrantedCodeNavigatesToItsRouteWithTheScreenName() {
        AgentContext admin = userWith(function(9, "USER_MANAGEMENT", "Quản lý người dùng", "/admin/users", 90));

        ToolOutcome outcome = open(admin, "USER_MANAGEMENT");

        assertThat(outcome).isEqualTo(new ToolOutcome.Navigate("/admin/users", "Quản lý người dùng",
            "Đã mở trang Quản lý người dùng."));
    }

    @Test
    void theModelsOwnWhitespaceAroundTheCodeIsTolerated() {
        AgentContext admin = userWith(function(9, "USER_MANAGEMENT", "Quản lý người dùng", "/admin/users", 90));

        assertThat(open(admin, " USER_MANAGEMENT ")).isInstanceOf(ToolOutcome.Navigate.class);
    }

    @Test
    void aCodeOfAnotherRoleIsRefusedAsTextAndNothingNavigates() {
        AgentContext learner = userWith(function(1, "PROFILE", "Hồ sơ cá nhân", "/profile", 10));

        assertThat(open(learner, "USER_MANAGEMENT")).isEqualTo(new ToolOutcome.Result(NOT_ALLOWED));
    }

    @Test
    void anInventedMissingOrNonTextCodeIsRefusedAsText() {
        AgentContext learner = userWith(function(1, "PROFILE", "Hồ sơ cá nhân", "/profile", 10));

        assertThat(open(learner, "DROP_TABLE")).isEqualTo(new ToolOutcome.Result(NOT_ALLOWED));
        assertThat(tool.run(JSON.createObjectNode(), learner, new TurnState()))
            .isEqualTo(new ToolOutcome.Result(NOT_ALLOWED));
        assertThat(tool.run(JSON.createObjectNode().put("functionCode", 9), learner, new TurnState()))
            .isEqualTo(new ToolOutcome.Result(NOT_ALLOWED));
    }

    @Test
    void aRouteWithAParameterIsNeverNavigatedTo() {
        AgentContext learner = userWith(function(7, "COURSE_DETAIL", "Chi tiết khóa học", "/learn/:courseId", 25));

        assertThat(open(learner, "COURSE_DETAIL")).isEqualTo(new ToolOutcome.Result(NOT_ALLOWED));
    }

    @Test
    void aUserWithNoOpenableScreenIsNotOfferedTheTool() {
        assertThat(tool.availableTo(userWith())).isFalse();
        assertThat(tool.availableTo(userWith(function(7, "COURSE_DETAIL", "Chi tiết", "/learn/:courseId", 25))))
            .isFalse();
        assertThat(tool.availableTo(userWith(function(1, "PROFILE", "Hồ sơ cá nhân", "/profile", 10)))).isTrue();
    }

    @Test
    void openingAScreenActsForTheUserAndReadsNoSharedContent() {
        assertThat(tool.actsForUser()).isTrue();
        assertThat(tool.readsSharedContent()).isFalse();
    }
}
