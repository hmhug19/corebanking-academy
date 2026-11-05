package vn.com.coreacademy.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import vn.com.coreacademy.identity.enums.AccessRequestKind;

/**
 * Limits the public access-request endpoints: 5 calls per IP in 10 minutes and 3 per kind and staff code in an
 * hour. A call over a limit answers 429 with {@code Retry-After} and is not counted, so it cannot extend its own
 * wait. Counts live in memory of this instance only, which is enough for one backend node; several nodes need the
 * counts in the database or a gateway rule.
 *
 * <p>Nothing here is an endpoint: the register and reset endpoints call {@link #check} first.
 */
@Component
public class AccessRequestThrottle {
    static final int PER_IP = 5;
    static final Duration IP_WINDOW = Duration.ofMinutes(10);
    static final int PER_KEY = 3;
    static final Duration KEY_WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Map<String, Deque<Instant>> byIp = new HashMap<>();
    private final Map<String, Deque<Instant>> byKey = new HashMap<>();

    public AccessRequestThrottle() {
        this(Clock.systemUTC());
    }

    AccessRequestThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Counts the call, or throws a 429 {@link ResponseStatusException} when a limit is reached. */
    public synchronized void check(String ip, AccessRequestKind kind, String staffCode) {
        Instant now = clock.instant();
        String ipKey = ip == null ? "" : ip;
        String codeKey = kind.name() + ":" + (staffCode == null ? "" : staffCode.trim().toLowerCase(Locale.ROOT));

        long wait = Math.max(waitSeconds(byIp, ipKey, PER_IP, IP_WINDOW, now),
            waitSeconds(byKey, codeKey, PER_KEY, KEY_WINDOW, now));
        if (wait > 0) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(wait));
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, null, null) {
                @Override
                public HttpHeaders getHeaders() {
                    return headers;
                }
            };
        }

        byIp.computeIfAbsent(ipKey, k -> new ArrayDeque<>()).addLast(now);
        byKey.computeIfAbsent(codeKey, k -> new ArrayDeque<>()).addLast(now);
    }

    /** Seconds until the oldest call leaves the window when the bucket is full, else 0. Drops expired calls. */
    private static long waitSeconds(Map<String, Deque<Instant>> buckets, String key, int limit, Duration window,
                                    Instant now) {
        Deque<Instant> calls = buckets.get(key);
        if (calls == null) {
            return 0;
        }
        while (!calls.isEmpty() && !calls.peekFirst().plus(window).isAfter(now)) {
            calls.removeFirst();
        }
        if (calls.isEmpty()) {
            buckets.remove(key);
            return 0;
        }
        if (calls.size() < limit) {
            return 0;
        }
        long millis = Duration.between(now, calls.peekFirst().plus(window)).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
