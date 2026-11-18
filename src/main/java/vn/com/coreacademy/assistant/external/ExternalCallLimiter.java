package vn.com.coreacademy.assistant.external;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * At most {@code calls-per-minute} requests per Kết nối trợ lý ngoài in each calendar minute, counted on this
 * instance. The next minute starts from zero.
 */
@Component
public class ExternalCallLimiter {
    /** Above this many connections tracked, windows of past minutes are dropped. */
    private static final int PRUNE_ABOVE = 1_000;

    private record Window(Instant minute, int calls) {
    }

    private final int callsPerMinute;
    private final Clock clock;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public ExternalCallLimiter(ExternalAssistantProperties properties) {
        this(properties.callsPerMinute(), Clock.systemUTC());
    }

    ExternalCallLimiter(int callsPerMinute, Clock clock) {
        this.callsPerMinute = callsPerMinute;
        this.clock = clock;
    }

    public int callsPerMinute() {
        return callsPerMinute;
    }

    /** Counts a call of the connection; null when it may go on, else how long until the next minute. */
    public Duration admit(long connectionId) {
        Instant now = clock.instant();
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        // Every call of the minute is counted, refused ones too; only the first callsPerMinute go on.
        Window window = windows.compute(connectionId, (id, current) -> current == null
            || !current.minute().equals(minute) ? new Window(minute, 1) : new Window(minute, current.calls() + 1));
        if (windows.size() > PRUNE_ABOVE) {
            windows.values().removeIf(item -> item.minute().isBefore(minute));
        }
        return window.calls() <= callsPerMinute ? null : Duration.between(now, minute.plus(1, ChronoUnit.MINUTES));
    }
}
