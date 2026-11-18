package vn.com.coreacademy.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.tool.ReadProfileTool;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.identity.dto.UserInfo;

import static org.assertj.core.api.Assertions.assertThat;

class ReadProfileToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReadProfileTool tool = new ReadProfileTool();

    private static UserInfo user(String fullName, String email, String position) {
        return new UserInfo("000004", "learner1", fullName, email, "ACTIVE", "LOCAL", false, 1L, "HO", "Hội sở", "ORG",
            2L, "T1", "Nhóm 1", position, null, null, null, null, null, null, "D1", "Phòng Đào tạo", null, null, null,
            null);
    }

    private String read(UserInfo profile) {
        AgentContext context = new AgentContext("000004", profile, null);
        return ((ToolOutcome.Result) tool.run(JSON.createObjectNode(), context, new TurnState())).text();
    }

    @Test
    void itReturnsTheCallersOwnProfileFieldsFromTheContext() {
        String text = read(user("Nguyễn Văn A", "a@example.com", "Chuyên viên"));

        assertThat(text).contains("Họ tên: Nguyễn Văn A").contains("Email: a@example.com")
            .contains("Chức danh: Chuyên viên").contains("Đơn vị: Hội sở").contains("Nhóm: Nhóm 1")
            .contains("Phòng ban: Phòng Đào tạo");
    }

    @Test
    void aFieldTheProfileDoesNotHaveIsSaidSoInsteadOfBeingLeftForTheModelToInvent() {
        String text = read(user("Nguyễn Văn A", null, " "));

        assertThat(text).contains("Email: chưa có thông tin").contains("Chức danh: chưa có thông tin");
    }

    @Test
    void itTakesNoArgumentSoItCannotReadSomeoneElseAndNeverActsForTheUser() {
        AgentContext context = new AgentContext("000004", user("A", "a@x", "B"), null);

        assertThat(tool.definition(context).parameters().path("properties").isEmpty()).isTrue();
        assertThat(tool.actsForUser()).isFalse();
        assertThat(tool.readsSharedContent()).isFalse();
        assertThat(tool.availableTo(context)).isTrue();
        assertThat(tool.availableTo(new AgentContext("000004", null, null))).isFalse();
    }
}
