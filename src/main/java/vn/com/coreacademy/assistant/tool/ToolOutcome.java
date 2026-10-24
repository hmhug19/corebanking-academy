package vn.com.coreacademy.assistant.tool;

import vn.com.coreacademy.assistant.action.PendingAction;

/** What a tool did. {@link Navigate} and {@link ActionRequired} end the turn; {@link Result} goes back to the model. */
public sealed interface ToolOutcome {
    /** Text for the model, appended as a {@code tool} message; the loop continues. */
    record Result(String text) implements ToolOutcome {
    }

    /** Terminal: the app navigates to {@code path}; {@code text} is the final answer. */
    record Navigate(String path, String title, String text) implements ToolOutcome {
    }

    /** Terminal: {@code action} waits for the user's confirmation; {@code text} is the final answer. */
    record ActionRequired(PendingAction action, String text) implements ToolOutcome {
    }
}
