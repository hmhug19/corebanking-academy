package vn.com.coreacademy.assistant.action;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.assistant.dto.PendingActionView;

/**
 * A data change the model proposed. {@code arguments} are the validated values the tool will apply on confirm;
 * {@code changes} is what the user was shown, with the values read from the server at proposal time.
 */
public record PendingAction(
    String id,
    String userId,
    String tool,
    JsonNode arguments,
    String title,
    List<PendingActionView.Change> changes,
    Instant createdAt,
    Instant expiresAt) {

    public PendingActionView view() {
        return new PendingActionView(id, tool, title, changes, expiresAt);
    }
}
