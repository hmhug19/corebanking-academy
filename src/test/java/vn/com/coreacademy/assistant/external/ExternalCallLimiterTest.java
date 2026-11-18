package vn.com.coreacademy.assistant.external;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalCallLimiterTest {
    /** A clock the test moves. */
    private static final class MovingClock extends Clock {
        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void theCallAfterTheLimitWaitsForTheNextMinuteAndOtherConnectionsAreNotAffected() {
        MovingClock clock = new MovingClock(Instant.parse("2026-11-18T09:00:15Z"));
        ExternalCallLimiter limiter = new ExternalCallLimiter(3, clock);

        assertThat(limiter.admit(1)).isNull();
        assertThat(limiter.admit(1)).isNull();
        assertThat(limiter.admit(1)).isNull();
        assertThat(limiter.admit(1)).isEqualTo(Duration.ofSeconds(45));
        assertThat(limiter.admit(2)).as("another connection").isNull();

        clock.now = Instant.parse("2026-11-18T09:00:59Z");
        assertThat(limiter.admit(1)).isEqualTo(Duration.ofSeconds(1));
        clock.now = Instant.parse("2026-11-18T09:01:00Z");
        assertThat(limiter.admit(1)).as("the next minute works again").isNull();
    }
}
