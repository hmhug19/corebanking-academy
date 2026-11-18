package vn.com.coreacademy.assistant.external;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.drafting.CourseDraftingService;
import vn.com.coreacademy.assistant.drafting.DraftingStates;
import vn.com.coreacademy.assistant.drafting.DraftingViews.SessionView;
import vn.com.coreacademy.assistant.drafting.ExternalDrafting;
import vn.com.coreacademy.assistant.drafting.ExternalDrafting.ProposalRefused;
import vn.com.coreacademy.common.dto.FieldViolation;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;

/**
 * What each tool of the Trợ lý ngoài does, as the connection's user: the same service methods, role and owner checks
 * as the web endpoints. A refusal is a tool error with a short English message the model can act on (which argument,
 * which state); it never echoes stored content. Only sessions a Trợ lý ngoài started are reachable here.
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class ExternalAssistantTools {
    public static final String START = "start_course_drafting";
    public static final String REFERENCE_DATA = "get_reference_data";
    public static final String GET_SESSION = "get_drafting_session";
    public static final String PROPOSE = "propose_step";
    public static final String STOP = "stop_course_drafting";

    private final CourseDraftingService sessions;
    private final ExternalDrafting drafting;
    private final ExternalAssistantProperties properties;
    private final ObjectMapper json;

    /**
     * A step as the Trợ lý ngoài reads it: {@code confirmed} is what the author confirmed there, their edits included
     * (null until confirmed); the next step builds on it.
     */
    public record StepView(int step, String state, JsonNode proposal, List<String> notes, String error,
                           JsonNode confirmed) {
    }

    /**
     * A session as the Trợ lý ngoài reads it: {@code waitingStep} is the step that accepts {@code propose_step} now
     * (null when none does), {@code next} what to do next, in plain words.
     */
    public record SessionResult(long sessionId, String status, int currentStep, Integer waitingStep,
                                String sourceName, String editorUrl, List<StepView> steps, String next) {
    }

    public CallToolResult start(Map<String, Object> arguments) {
        return run(() -> session(sessions.startExternal(text(arguments, "source_name"))));
    }

    public CallToolResult referenceData(Map<String, Object> arguments) {
        return run(drafting::referenceData);
    }

    public CallToolResult get(Map<String, Object> arguments) {
        return run(() -> session(sessions.getExternal(sessionId(arguments))));
    }

    public CallToolResult propose(Map<String, Object> arguments) {
        return run(() -> {
            long sessionId = sessionId(arguments);
            long step = wholeNumber(arguments, "step");
            Object proposal = arguments.get("proposal");
            if (proposal == null) {
                throw new ArgumentException("proposal is required: an object with the fields of the step.");
            }
            return session(drafting.propose(sessionId, (int) Math.min(step, Integer.MAX_VALUE),
                json.valueToTree(proposal)));
        });
    }

    public CallToolResult stop(Map<String, Object> arguments) {
        return run(() -> session(sessions.stopExternal(sessionId(arguments))));
    }

    // ---- results ---------------------------------------------------------------------------------------------------

    SessionResult session(SessionView view) {
        Integer waiting = view.steps().stream()
            .filter(step -> DraftingStates.ACTIVE.equals(view.status()) && step.step() == view.currentStep()
                && DraftingStates.PROPOSING.equals(step.state()))
            .map(step -> step.step()).findFirst().orElse(null);
        Map<Integer, JsonNode> confirmed = drafting.confirmed(view.id());
        List<StepView> steps = view.steps().stream()
            .map(step -> new StepView(step.step(), step.state(), step.proposal(), step.notes(), step.error(),
                confirmed.get(step.step())))
            .toList();

        return new SessionResult(view.id(), view.status(), view.currentStep(), waiting, view.sourceName(),
            editorUrl(view), steps, next(view, waiting));
    }

    private String editorUrl(SessionView view) {
        String base = properties.appBaseUrl().replaceAll("/+$", "");
        return view.courseId() == null ? base + "/admin/courses/new?session=" + view.id()
            : base + "/admin/courses/" + view.courseId() + "/edit?step=" + (view.currentStep() - 1);
    }

    private static String next(SessionView view, Integer waiting) {
        if (!DraftingStates.ACTIVE.equals(view.status())) {
            return "The session is " + view.status() + ": nothing more to propose. Tell the author the page holds "
                + "what was confirmed; do not say the course was created or published.";
        }
        if (waiting != null) {
            return "Step " + waiting + " waits for your proposal: call propose_step for it.";
        }
        return "Step " + view.currentStep() + " is with the author. Ask them to open editorUrl, edit the fields and "
            + "press \"Chốt bước\", then wait until they say \"tiếp\".";
    }

    // ---- arguments and errors --------------------------------------------------------------------------------------

    /** A tool argument that is missing or of the wrong type. */
    static final class ArgumentException extends RuntimeException {
        ArgumentException(String message) {
            super(message);
        }
    }

    static long sessionId(Map<String, Object> arguments) {
        return wholeNumber(arguments, "session_id");
    }

    static long wholeNumber(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
            && number.longValue() > 0) {
            return number.longValue();
        }
        if (value instanceof String text && text.strip().matches("[1-9][0-9]{0,17}")) {
            return Long.parseLong(text.strip());
        }
        throw new ArgumentException(name + " is required: a positive whole number.");
    }

    static String text(Map<String, Object> arguments, String name) {
        Object value = arguments == null ? null : arguments.get(name);
        if (value instanceof String text) {
            return text;
        }
        throw new ArgumentException(name + " is required: text.");
    }

    /** Runs a tool body as the caller and turns its answer, or its refusal, into a tool result. */
    CallToolResult run(Supplier<Object> body) {
        try {
            return CallToolResult.builder().addTextContent(json.writeValueAsString(body.get())).isError(false)
                .build();
        } catch (ArgumentException | ProposalRefused exception) {
            return refusal(exception.getMessage());
        } catch (BusinessException exception) {
            return refusal(message(exception));
        } catch (AccessDeniedException exception) {
            return refusal("Your account may not draft courses with the assistant.");
        } catch (JsonProcessingException | RuntimeException exception) {
            log.error("External assistant tool failed exceptionType={}", exception.getClass().getName());
            return refusal("The server could not do this now. Try again later.");
        }
    }

    static CallToolResult refusal(String message) {
        return CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    /** An English message naming the argument and the reason, never the stored values. */
    static String message(BusinessException exception) {
        if (exception.getResponseCode() == ResponseCode.RESOURCE_NOT_FOUND) {
            return "Drafting session not found among your sessions started by an external assistant.";
        }
        List<FieldViolation> errors = exception.getErrors();
        if (errors.isEmpty()) {
            return "Refused: " + exception.getResponseCode().getCode() + ".";
        }
        FieldViolation first = errors.get(0);
        return switch (first.code()) {
            case "INVALID_LENGTH" -> first.field() + " has the wrong length (source_name: 1 to 255 characters).";
            case "NO_UNIT" -> "Your account belongs to no unit, so no course can be drafted for it.";
            default -> "Refused: " + first.field() + " (" + first.code() + ").";
        };
    }
}
