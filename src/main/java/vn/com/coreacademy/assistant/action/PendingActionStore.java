package vn.com.coreacademy.assistant.action;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.dto.PendingActionView.Change;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;

/**
 * Data changes the model proposed and the user has not answered yet. They live in memory only: an entry expires after
 * the configured TTL, belongs to the user who caused it and is removed by whoever answers it first, so a double click
 * runs once. A restart drops everything; the app then tells the user the request expired.
 */
@Component
public class PendingActionStore {
    private static final int MAX_PER_USER = 20;

    private final ConcurrentHashMap<String, PendingAction> actions = new ConcurrentHashMap<>();
    private final AssistantProperties properties;
    private final Clock clock;

    @Autowired
    public PendingActionStore(AssistantProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public PendingActionStore(AssistantProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Stores a new action for {@code userId}; the user's oldest one is dropped when this is their 21st. */
    public synchronized PendingAction add(String userId, String tool, JsonNode arguments, String title,
                                          List<Change> changes) {
        Instant now = clock.instant();
        PendingAction action = new PendingAction(UUID.randomUUID().toString(), userId, tool, arguments, title,
            List.copyOf(changes), now, now.plus(properties.actionTtl()));

        List<PendingAction> own = actions.values().stream().filter(candidate -> candidate.userId().equals(userId))
            .sorted(Comparator.comparing(PendingAction::createdAt)).toList();
        if (own.size() >= MAX_PER_USER) {
            actions.remove(own.get(0).id());
        }
        actions.put(action.id(), action);

        return action;
    }

    /**
     * Removes and returns the action when it is live and {@code actorId} created it. A missing, expired, cancelled or
     * foreign id all fail the same way, so an id reveals nothing about other users; a foreign id is left in place.
     */
    public PendingAction take(String id, String actorId) {
        AtomicReference<PendingAction> taken = new AtomicReference<>();
        Instant now = clock.instant();
        actions.computeIfPresent(id, (key, action) -> {
            if (!action.expiresAt().isAfter(now)) {
                return null;
            }
            if (!action.userId().equals(actorId)) {
                return action;
            }
            taken.set(action);
            return null;
        });
        if (taken.get() == null) {
            throw new BusinessException(AssistantResponseCode.ACTION_NOT_FOUND);
        }

        return taken.get();
    }

    /** Drops the action without running it; the same not-found rule as {@link #take}. */
    public void cancel(String id, String actorId) {
        take(id, actorId);
    }

    /** Removes every expired entry; runs each minute so abandoned cards do not accumulate. */
    @Scheduled(fixedDelayString = "PT1M")
    public void sweep() {
        Instant now = clock.instant();
        actions.values().removeIf(action -> !action.expiresAt().isAfter(now));
    }

    /** Entries currently held, expired ones included until swept. */
    public int size() {
        return actions.size();
    }
}
