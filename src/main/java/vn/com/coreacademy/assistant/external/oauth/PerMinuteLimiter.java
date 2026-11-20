package vn.com.coreacademy.assistant.external.oauth;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** At most {@code perMinute} admissions per key in each calendar minute, counted on this instance. */
final class PerMinuteLimiter {
    private static final int PRUNE_ABOVE = 1_000;

    private record Window(Instant minute, int count) {
    }

    private final int perMinute;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    PerMinuteLimiter(int perMinute, Clock clock) {
        this.perMinute = perMinute;
        this.clock = clock;
    }

    /** Counts one for the key; false when it is over the limit for this minute. */
    boolean admit(String key) {
        Instant minute = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        Window window = windows.compute(key, (k, current) -> current == null || !current.minute().equals(minute)
            ? new Window(minute, 1) : new Window(minute, current.count() + 1));
        if (windows.size() > PRUNE_ABOVE) {
            windows.values().removeIf(item -> item.minute().isBefore(minute));
        }
        return window.count() <= perMinute;
    }
}
