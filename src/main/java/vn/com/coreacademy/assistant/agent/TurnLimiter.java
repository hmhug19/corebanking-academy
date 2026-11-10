package vn.com.coreacademy.assistant.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.config.AssistantLimits;
import vn.com.coreacademy.assistant.config.AssistantProperties;

/**
 * Keeps the local model from being queued up, for the agent and the document chat alike: one turn per user at a time,
 * at most {@code max-concurrent-turns} on the server, and {@code questions-per-hour} per user over a rolling hour.
 * A turn is taken inside the stream body and given back in its {@code finally}, so nothing can hold a place after its
 * stream has ended. Only an admitted question counts toward the hour; a refusal holds and costs nothing.
 */
@Component
public class TurnLimiter {
    static final Duration WINDOW = Duration.ofHours(1);

    private final Semaphore places;
    private final int questionsPerHour;
    private final Clock clock;
    private final Set<String> answering = ConcurrentHashMap.newKeySet();
    private final Map<String, Deque<Instant>> asked = new ConcurrentHashMap<>();

    @Autowired
    public TurnLimiter(AssistantProperties properties, AssistantLimits limits) {
        this(properties, limits, Clock.systemUTC());
    }

    public TurnLimiter(AssistantProperties properties, AssistantLimits limits, Clock clock) {
        this.places = new Semaphore(properties.maxConcurrentTurns());
        this.questionsPerHour = limits.questionsPerHour();
        this.clock = clock;
    }

    /** The answer to "may this user ask now?": a running turn to close, or the reason there is none. */
    public sealed interface Admission {
        /** The user may ask; close the turn when the answer has ended. */
        record Started(Turn turn) implements Admission {
        }

        /** The user already has a turn running, or the server is full. */
        record Busy() implements Admission {
        }

        /** The user has used the hour's questions; {@code retryAfter} is when the oldest one leaves the window. */
        record RateLimited(Duration retryAfter) implements Admission {
            /** "Thử lại sau X phút", rounded up so the user is never told to come back too early. */
            public String message() {
                long minutes = Math.max(1, (retryAfter.toSeconds() + 59) / 60);
                return "Bạn đã hỏi nhiều, thử lại sau " + minutes + " phút.";
            }
        }
    }

    public Admission admit(String userId) {
        if (!answering.add(userId)) {
            return new Admission.Busy();
        }
        if (!places.tryAcquire()) {
            answering.remove(userId);
            return new Admission.Busy();
        }
        Duration wait = recordQuestion(userId);
        if (wait != null) {
            places.release();
            answering.remove(userId);
            return new Admission.RateLimited(wait);
        }

        return new Admission.Started(new Turn(userId));
    }

    /** Null when the question was counted; otherwise how long until the user may ask again. */
    private Duration recordQuestion(String userId) {
        Instant now = clock.instant();
        Duration[] refused = new Duration[1];
        asked.compute(userId, (key, times) -> {
            Deque<Instant> window = times == null ? new ArrayDeque<>() : times;
            while (!window.isEmpty() && !window.peekFirst().plus(WINDOW).isAfter(now)) {
                window.pollFirst();
            }
            if (window.size() >= questionsPerHour) {
                refused[0] = Duration.between(now, window.peekFirst().plus(WINDOW));
            } else {
                window.addLast(now);
            }
            return window;
        });

        return refused[0];
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
