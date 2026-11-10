package vn.com.coreacademy.assistant.action;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.dto.PendingActionView.Change;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;

/**
 * Data changes the model proposed and the user has not answered yet, kept in the database so a restart loses nothing
 * and every instance sees the same ones. An action expires after the configured TTL, belongs to the user who caused it
 * and is answered once: the answer is one conditional UPDATE, so of two concurrent confirms exactly one wins. After the
 * answer the row stays, with its outcome, until the conversation that shows it is deleted.
 */
@Component
public class PendingActionStore {
    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";

    private static final int MAX_PER_USER = 20;
    private static final int MAX_MESSAGE = 500;
    private static final TypeReference<List<Change>> CHANGES = new TypeReference<>() {
    };

    private final AssistantProperties properties;
    private final PendingActionRepository actions;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public PendingActionStore(AssistantProperties properties, PendingActionRepository actions, ObjectMapper json) {
        this(properties, actions, json, Clock.systemUTC());
    }

    public PendingActionStore(AssistantProperties properties, PendingActionRepository actions, ObjectMapper json,
                              Clock clock) {
        this.properties = properties;
        this.actions = actions;
        this.json = json;
        this.clock = clock;
    }

    /** Stores a new action for {@code userId}; the user's oldest live one is cancelled when this is their 21st. */
    @Transactional
    public PendingAction add(String userId, String tool, JsonNode arguments, String title, List<Change> changes) {
        Instant now = clock.instant();
        AssistantPendingAction draft = new AssistantPendingAction();
        draft.setId(UUID.randomUUID().toString());
        draft.setOwnerId(userId);
        draft.setTool(tool);
        draft.setArguments(write(arguments));
        draft.setTitle(title);
        draft.setChanges(write(List.copyOf(changes)));
        // The column keeps microseconds; trimming here keeps what add returns equal to what take reads back.
        draft.setExpiresAt(now.plus(properties.actionTtl()).truncatedTo(ChronoUnit.MICROS));
        // Not the same instance: the assigned id makes save a merge, and only the returned one has create_time.
        AssistantPendingAction row = actions.saveAndFlush(draft);

        long excess = actions.countLive(userId, now) - MAX_PER_USER;
        if (excess > 0) {
            for (AssistantPendingAction oldest : actions.live(userId, now, PageRequest.of(0, (int) excess))) {
                actions.answer(oldest.getId(), userId, CANCELLED, now);
            }
        }

        return new PendingAction(row.getId(), userId, tool, arguments, title, List.copyOf(changes),
            row.getCreateTime(), row.getExpiresAt());
    }

    /**
     * Marks the action confirmed and returns it when it is live and {@code actorId} created it. A missing, expired,
     * answered or foreign id all fail the same way, so an id reveals nothing about other users; a foreign id is left
     * untouched. The caller then runs the tool and records the outcome with {@link #succeeded} or {@link #failed}.
     */
    @Transactional
    public PendingAction take(String id, String actorId) {
        if (actions.answer(id, actorId, CONFIRMED, clock.instant()) != 1) {
            throw new BusinessException(AssistantResponseCode.ACTION_NOT_FOUND);
        }
        AssistantPendingAction row = actions.findById(id)
            .orElseThrow(() -> new BusinessException(AssistantResponseCode.ACTION_NOT_FOUND));

        return new PendingAction(row.getId(), row.getOwnerId(), row.getTool(), read(row.getArguments(), JsonNode.class),
            row.getTitle(), read(row.getChanges(), CHANGES), row.getCreateTime(), row.getExpiresAt());
    }

    /** Cancels the action without running it; the same not-found rule as {@link #take}. */
    @Transactional
    public void cancel(String id, String actorId) {
        if (actions.answer(id, actorId, CANCELLED, clock.instant()) != 1) {
            throw new BusinessException(AssistantResponseCode.ACTION_NOT_FOUND);
        }
    }

    /** What the user was told after a confirmed action ran, shown again when the conversation is reopened. */
    @Transactional
    public void succeeded(String id, String message) {
        actions.setMessage(id, cut(message));
    }

    /** A confirmed action the tool then refused (for example the data changed): its row says so. */
    @Transactional
    public void failed(String id, String message) {
        actions.fail(id, cut(message));
    }

    private static String cut(String message) {
        if (message == null) {
            return null;
        }

        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialise a pending action", exception);
        }
    }

    private <T> T read(String text, Class<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read a pending action", exception);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read a pending action", exception);
        }
    }
}
