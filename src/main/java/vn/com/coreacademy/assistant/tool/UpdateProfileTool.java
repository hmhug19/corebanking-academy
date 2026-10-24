package vn.com.coreacademy.assistant.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.action.PendingAction;
import vn.com.coreacademy.assistant.action.PendingActionStore;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.dto.ActionResultView;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.assistant.tool.ProfileChange.Field;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.dto.ProfileUpdateRequest;
import vn.com.coreacademy.identity.service.AuthService;
import vn.com.coreacademy.identity.service.SelfProfileService;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;

/**
 * Proposes a change of the user's own full name, email or position. It never changes data in the loop: it validates
 * the values with the rules of {@code PATCH /auth/me}, stores a pending action and ends the turn with a card. The
 * change runs only in {@link #execute}, after the user confirms, through {@link SelfProfileService} as that user.
 */
@Component
@RequiredArgsConstructor
public class UpdateProfileTool implements AgentTool {
    private static final String ASK_WHICH_FIELD = "Bạn muốn đổi trường nào: họ tên, email hay chức danh?";
    private static final String NOTHING_TO_CHANGE = "Hồ sơ đã có đúng giá trị này, không cần thay đổi.";
    private static final String TITLE = "Cập nhật hồ sơ cá nhân";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PendingActionStore store;
    private final AuthService auth;
    private final SelfProfileService profiles;
    private final Validator validator;

    @Override
    public String name() {
        return ToolDefinitions.UPDATE_PROFILE;
    }

    /** An SSO account's fields belong to the identity provider, and a temporary password blocks profile changes. */
    @Override
    public boolean availableTo(AgentContext context) {
        return "LOCAL".equals(context.profile().authProvider()) && !context.profile().mustChangePassword();
    }

    @Override
    public ToolDefinition definition(AgentContext context) {
        return ToolDefinitions.updateProfile();
    }

    @Override
    public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
        // Unknown properties are ignored; "from" values are never read from the model, only from the profile.
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        List<Field> supplied = new ArrayList<>();
        for (Field field : Field.values()) {
            JsonNode value = arguments.path(field.json());
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (!value.isTextual()) {
                return new ToolOutcome.Result(field.label() + " phải là văn bản.");
            }
            supplied.add(field);
            field.apply(request, ProfileChange.blankToNull(value.asText()));
        }
        if (supplied.isEmpty()) {
            return new ToolOutcome.Result(ASK_WHICH_FIELD);
        }

        String invalid = firstViolation(request);
        if (invalid != null) {
            return new ToolOutcome.Result(invalid);
        }

        List<ProfileChange> changes = supplied.stream()
            .map(field -> new ProfileChange(field, field.current(context.profile()), newValue(request, field)))
            .filter(change -> !Objects.equals(change.from(), change.to())).toList();
        if (changes.isEmpty()) {
            return new ToolOutcome.Result(NOTHING_TO_CHANGE);
        }

        ObjectNode newValues = JSON.createObjectNode();
        changes.forEach(change -> newValues.put(change.field().json(), change.to()));
        PendingAction action = store.add(context.userId(), name(), newValues, TITLE,
            changes.stream().map(ProfileChange::toView).toList());
        return new ToolOutcome.ActionRequired(action, "Vui lòng kiểm tra và xác nhận thay đổi bên dưới.");
    }

    /**
     * Runs after the user confirmed, on the request thread with their security context. Refuses when the profile no
     * longer holds the values the card showed, so the user never confirms a change against stale data.
     */
    @Override
    public ActionResultView execute(PendingAction action, AgentContext context) {
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        for (var change : action.changes()) {
            Field field = Field.of(change.field());
            if (!Objects.equals(field.current(context.profile()), ProfileChange.blankToNull(change.from()))) {
                throw new BusinessException(AssistantResponseCode.ACTION_STALE);
            }
            JsonNode value = action.arguments().path(change.field());
            field.apply(request, value.isNull() ? null : value.asText());
        }

        profiles.update(request);
        return new ActionResultView(action.id(), name(), "Đã cập nhật hồ sơ.", auth.me());
    }

    private String newValue(ProfileUpdateRequest request, Field field) {
        return switch (field) {
            case FULL_NAME -> request.getFullName();
            case EMAIL -> request.getEmail();
            case POSITION_NAME -> request.getPositionName();
        };
    }

    /** The same constraints as {@code PATCH /auth/me}, with a message the model can pass on to the user. */
    private String firstViolation(ProfileUpdateRequest request) {
        if (request.has("fullName") && request.getFullName() == null) {
            return "Họ tên phải từ 1 đến 255 ký tự.";
        }
        for (ConstraintViolation<ProfileUpdateRequest> violation : validator.validate(request)) {
            return switch (violation.getPropertyPath().toString()) {
                case "fullName" -> "Họ tên phải từ 1 đến 255 ký tự.";
                case "email" -> "Email không hợp lệ hoặc dài quá 100 ký tự.";
                default -> "Chức danh dài tối đa 255 ký tự.";
            };
        }

        return null;
    }
}
