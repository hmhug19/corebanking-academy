package vn.com.coreacademy.assistant;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import vn.com.coreacademy.assistant.action.PendingAction;
import vn.com.coreacademy.assistant.action.PendingActionStore;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.dto.ActionResultView;
import vn.com.coreacademy.assistant.dto.PendingActionView.Change;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.assistant.tool.UpdateProfileTool;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.dto.MeResponse;
import vn.com.coreacademy.identity.dto.ProfileUpdateRequest;
import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.dto.UserRights;
import vn.com.coreacademy.identity.service.AuthService;
import vn.com.coreacademy.identity.service.SelfProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateProfileToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private final AuthService auth = mock(AuthService.class);
    private final SelfProfileService profiles = mock(SelfProfileService.class);
    private final PendingActionStore store = new PendingActionStore(new AssistantProperties(4, 20,
        Duration.ofMinutes(10)));
    private final UpdateProfileTool tool = new UpdateProfileTool(store, auth, profiles, VALIDATOR);

    private static UserInfo user(String provider, boolean mustChangePassword, String fullName, String email,
                                 String position) {
        return new UserInfo("000004", "hocvien", fullName, email, "active", provider, mustChangePassword, 1L, "ORG",
            "Đơn vị", "UNIT", null, null, null, position, null, null, null, null, null, null, null, null, null, null,
            null, null);
    }

    private static AgentContext context(UserInfo user) {
        return new AgentContext(user.userId(), user, new UserRights(List.of(), List.of(), Map.of()));
    }

    private static final AgentContext LEARNER = context(user("LOCAL", false, "Học viên Demo", "hv@example.test",
        "Chuyên viên"));

    private static ObjectNode args() {
        return JSON.createObjectNode();
    }

    private ToolOutcome run(ObjectNode arguments) {
        return tool.run(arguments, LEARNER, new TurnState());
    }

    private static String text(ToolOutcome outcome) {
        return ((ToolOutcome.Result) outcome).text();
    }

    @Test
    void theDefinitionTakesThreeOptionalFields() {
        var definition = tool.definition(LEARNER);

        assertThat(tool.name()).isEqualTo("update_profile");
        assertThat(definition.name()).isEqualTo("update_profile");
        assertThat(definition.parameters().path("properties").fieldNames()).toIterable()
            .containsExactly("fullName", "email", "positionName");
        assertThat(definition.parameters().has("required")).isFalse();
    }

    @Test
    void onlyLocalAccountsWithNoPendingPasswordChangeAreOfferedTheTool() {
        assertThat(tool.availableTo(LEARNER)).isTrue();
        assertThat(tool.availableTo(context(user("SSO", false, "A", "a@b.vn", null)))).isFalse();
        assertThat(tool.availableTo(context(user("LOCAL", true, "A", "a@b.vn", null)))).isFalse();
    }

    @Test
    void noKnownFieldAsksWhichOneTheUserWants() {
        assertThat(text(run(args()))).isEqualTo("Bạn muốn đổi trường nào: họ tên, email hay chức danh?");
        assertThat(text(run(args().put("role", "ADMIN")))).isEqualTo(
            "Bạn muốn đổi trường nào: họ tên, email hay chức danh?");
    }

    @Test
    void unknownPropertiesAreIgnoredNextToAKnownOne() {
        ToolOutcome outcome = run(args().put("positionName", "Trưởng nhóm").put("role", "ADMIN").put("orgId", 1));

        PendingAction action = ((ToolOutcome.ActionRequired) outcome).action();
        assertThat(action.changes()).extracting(Change::field).containsExactly("positionName");
        assertThat(action.arguments().fieldNames()).toIterable().containsExactly("positionName");
    }

    @Test
    void validationFailuresAreResultsTheModelCanRelay() {
        assertThat(text(run(args().put("fullName", "   ")))).isEqualTo("Họ tên phải từ 1 đến 255 ký tự.");
        assertThat(text(run(args().put("fullName", "x".repeat(256))))).isEqualTo("Họ tên phải từ 1 đến 255 ký tự.");
        assertThat(text(run(args().put("email", "không-phải-email")))).isEqualTo(
            "Email không hợp lệ hoặc dài quá 100 ký tự.");
        assertThat(text(run(args().put("email", "a".repeat(95) + "@b.vn")))).isEqualTo(
            "Email không hợp lệ hoặc dài quá 100 ký tự.");
        assertThat(text(run(args().put("positionName", "p".repeat(256))))).isEqualTo(
            "Chức danh dài tối đa 255 ký tự.");
        assertThat(text(run(args().put("email", 5)))).isEqualTo("Email phải là văn bản.");
        assertThat(store.size()).isZero();
    }

    @Test
    void aProposalNeedsNoCardUntilEveryFieldIsValid() {
        assertThat(run(args().put("fullName", "Nguyễn Văn Minh").put("email", "sai"))).isInstanceOf(
            ToolOutcome.Result.class);
        assertThat(store.size()).isZero();
    }

    @Test
    void unchangedFieldsAreDroppedAndNothingLeftIsAResult() {
        ToolOutcome partly = run(args().put("fullName", "  Học viên Demo ").put("positionName", "Trưởng nhóm"));
        assertThat(((ToolOutcome.ActionRequired) partly).action().changes()).extracting(Change::field)
            .containsExactly("positionName");

        ToolOutcome none = run(args().put("fullName", "Học viên Demo").put("email", "hv@example.test"));
        assertThat(text(none)).isEqualTo("Hồ sơ đã có đúng giá trị này, không cần thay đổi.");
    }

    @Test
    void theCardHasLabelsOldValuesAndTheTerminalText() {
        ToolOutcome outcome = run(args().put("fullName", "Nguyễn Văn Minh").put("email", " minh@example.test ")
            .put("positionName", "Chuyên viên chính"));

        ToolOutcome.ActionRequired required = (ToolOutcome.ActionRequired) outcome;
        PendingAction action = required.action();
        assertThat(required.text()).isEqualTo("Vui lòng kiểm tra và xác nhận thay đổi bên dưới.");
        assertThat(action.tool()).isEqualTo("update_profile");
        assertThat(action.userId()).isEqualTo("000004");
        assertThat(action.title()).isEqualTo("Cập nhật hồ sơ cá nhân");
        assertThat(action.changes()).containsExactly(
            new Change("fullName", "Họ tên", "Học viên Demo", "Nguyễn Văn Minh"),
            new Change("email", "Email", "hv@example.test", "minh@example.test"),
            new Change("positionName", "Chức danh", "Chuyên viên", "Chuyên viên chính"));
        assertThat(store.take(action.id(), "000004")).isEqualTo(action);
    }

    @Test
    void anEmptyEmailOrPositionClearsTheValueAndAnAlreadyEmptyOneIsUnchanged() {
        AgentContext noPosition = context(user("LOCAL", false, "Học viên Demo", "hv@example.test", null));

        ToolOutcome clear = tool.run(args().put("email", "").put("positionName", ""), noPosition, new TurnState());

        PendingAction action = ((ToolOutcome.ActionRequired) clear).action();
        assertThat(action.changes()).containsExactly(new Change("email", "Email", "hv@example.test", null));
        assertThat(action.arguments().get("email").isNull()).isTrue();
    }

    @Test
    void executeUpdatesOnlyTheChangedFieldsAndReturnsTheRefreshedProfile() {
        ObjectNode newValues = args().put("positionName", "Trưởng nhóm");
        newValues.putNull("email");
        PendingAction action = new PendingAction("a1", "000004", "update_profile", newValues, "Cập nhật hồ sơ cá nhân",
            List.of(new Change("positionName", "Chức danh", "Chuyên viên", "Trưởng nhóm"),
                new Change("email", "Email", "hv@example.test", null)), null, null);
        MeResponse refreshed = new MeResponse(user("LOCAL", false, "Học viên Demo", null, "Trưởng nhóm"), List.of(),
            List.of(), List.of(), List.of(), Map.of());
        when(auth.me()).thenReturn(refreshed);

        ActionResultView result = tool.execute(action, LEARNER);

        ArgumentCaptor<ProfileUpdateRequest> request = ArgumentCaptor.captor();
        verify(profiles).update(request.capture());
        assertThat(request.getValue().getSuppliedFields()).containsExactlyInAnyOrder("positionName", "email");
        assertThat(request.getValue().getPositionName()).isEqualTo("Trưởng nhóm");
        assertThat(request.getValue().getEmail()).isNull();
        assertThat(result.actionId()).isEqualTo("a1");
        assertThat(result.tool()).isEqualTo("update_profile");
        assertThat(result.message()).isEqualTo("Đã cập nhật hồ sơ.");
        assertThat(result.data()).isSameAs(refreshed);
    }

    @Test
    void executeRefusesAProfileThatChangedSinceTheProposalAndUpdatesNothing() {
        PendingAction action = new PendingAction("a1", "000004", "update_profile",
            args().put("positionName", "Trưởng nhóm"), "Cập nhật hồ sơ cá nhân",
            List.of(new Change("positionName", "Chức danh", "Giám đốc", "Trưởng nhóm")), null, null);

        assertThatThrownBy(() -> tool.execute(action, LEARNER)).isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getResponseCode()).isEqualTo(AssistantResponseCode.ACTION_STALE));
        verify(profiles, never()).update(any());
    }

    @Test
    void aRefusalOfTheProfileServicePassesThrough() {
        PendingAction action = new PendingAction("a1", "000004", "update_profile",
            args().put("positionName", "Trưởng nhóm"), "Cập nhật hồ sơ cá nhân",
            List.of(new Change("positionName", "Chức danh", "Chuyên viên", "Trưởng nhóm")), null, null);
        org.mockito.Mockito.doThrow(new AccessDeniedException("SSO")).when(profiles).update(any());

        assertThatThrownBy(() -> tool.execute(action, LEARNER)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theModelsValuesNeverReachTheStaleCheckFromTheCard() {
        // The server reads "from" itself at proposal time: a model-supplied "from" is an unknown property and ignored.
        JsonNode arguments = args().put("positionName", "Trưởng nhóm").put("from", "Giám đốc");

        PendingAction action = ((ToolOutcome.ActionRequired) tool.run(arguments, LEARNER, new TurnState())).action();

        assertThat(action.changes().get(0).from()).isEqualTo("Chuyên viên");
    }

    @Test
    void proposingAChangeActsForTheUserAndReadsNoSharedContent() {
        assertThat(tool.actsForUser()).isTrue();
        assertThat(tool.readsSharedContent()).isFalse();
    }
}
