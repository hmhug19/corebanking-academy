package vn.com.coreacademy.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock a test moves by hand, so a rolling window or a time-to-live can be crossed without waiting. */
public final class MutableClock extends Clock {
    private Instant now;

    public MutableClock() {
        this(Instant.parse("2026-11-09T09:00:00Z"));
    }

    public MutableClock(Instant now) {
        this.now = now;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
