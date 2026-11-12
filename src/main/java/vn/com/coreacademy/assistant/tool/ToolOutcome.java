package vn.com.coreacademy.assistant.tool;

import java.util.Map;

import vn.com.coreacademy.assistant.action.PendingAction;

/** What a tool did. {@link Navigate} and {@link ActionRequired} end the turn; {@link Result} goes back to the model. */
public sealed interface ToolOutcome {
    /** Text for the model, appended as a {@code tool} message; the loop continues. */
    record Result(String text) implements ToolOutcome {
    }

    /**
     * Terminal: the app navigates to {@code path}; {@code text} is the final answer. {@code prefill}, when set, is what
     * the screen should open with (never trusted by the server, only a convenience for the form).
     */
    record Navigate(String path, String title, String text, Map<String, Object> prefill) implements ToolOutcome {
        public Navigate(String path, String title, String text) {
            this(path, title, text, null);
        }
    }

    /** Terminal: {@code action} waits for the user's confirmation; {@code text} is the final answer. */
    record ActionRequired(PendingAction action, String text) implements ToolOutcome {
    }
}
