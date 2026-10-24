package vn.com.coreacademy.assistant.agent;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.config.AssistantProperties;

/**
 * Keeps the local model from being queued up: one turn per user at a time, and at most
 * {@code max-concurrent-turns} on the server. A turn is taken inside the stream body and given back in its
 * {@code finally}, so nothing can hold a place after its stream has ended.
 */
@Component
public class TurnLimiter {
    private final Semaphore places;
    private final Set<String> answering = ConcurrentHashMap.newKeySet();

    public TurnLimiter(AssistantProperties properties) {
        this.places = new Semaphore(properties.maxConcurrentTurns());
    }

    /** Empty when the user already has a turn running or the server is full; a refusal holds nothing. */
    public Optional<Turn> tryStart(String userId) {
        if (!answering.add(userId)) {
            return Optional.empty();
        }
        if (!places.tryAcquire()) {
            answering.remove(userId);
            return Optional.empty();
        }

        return Optional.of(new Turn(userId));
    }

    /** A running turn; closing it more than once gives its place back once. */
    public final class Turn implements AutoCloseable {
        private final String userId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Turn(String userId) {
            this.userId = userId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                places.release();
                answering.remove(userId);
            }
        }
    }
}
