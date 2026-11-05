package vn.com.coreacademy.identity.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import vn.com.coreacademy.identity.enums.AccessRequestKind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessRequestThrottleTest {
    private static final class MovableClock extends java.time.Clock {
        private Instant now = Instant.parse("2026-11-05T00:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final AccessRequestThrottle throttle = new AccessRequestThrottle(clock);

    private void ask(String ip, String code) {
        throttle.check(ip, AccessRequestKind.REGISTER, code);
    }

    @Test
    void allowsFiveFromOneIpInTenMinutesAndRefusesTheSixth() {
        for (int i = 0; i < 5; i++) {
            ask("10.0.0.1", "00010" + i);
        }
        assertThatThrownBy(() -> ask("10.0.0.1", "000199")).isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> {
                var refusal = (ResponseStatusException) e;
                assertThat(refusal.getStatusCode().value()).isEqualTo(429);
                assertThat(refusal.getHeaders().getFirst("Retry-After")).isNotNull();
                assertThat(Long.parseLong(refusal.getHeaders().getFirst("Retry-After"))).isBetween(1L, 600L);
            });
    }

    @Test
    void anotherIpIsNotAffected() {
        for (int i = 0; i < 5; i++) {
            ask("10.0.0.1", "00010" + i);
        }
        assertThatCode(() -> ask("10.0.0.2", "000199")).doesNotThrowAnyException();
    }

    @Test
    void allowsThreePerStaffCodeInAnHourAcrossIpsAndRefusesTheFourth() {
        ask("10.0.0.1", "000123");
        ask("10.0.0.2", "000123");
        ask("10.0.0.3", "000123");
        assertThatThrownBy(() -> ask("10.0.0.4", "000123")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void treatsTheStaffCodeCaseInsensitivelyAndPerKind() {
        ask("10.0.0.1", "AB1");
        ask("10.0.0.2", "ab1");
        ask("10.0.0.3", "Ab1");
        assertThatThrownBy(() -> ask("10.0.0.4", "aB1")).isInstanceOf(ResponseStatusException.class);
        assertThatCode(() -> throttle.check("10.0.0.5", AccessRequestKind.PASSWORD_RESET, "ab1"))
            .doesNotThrowAnyException();
    }

    @Test
    void windowsExpire() {
        for (int i = 0; i < 5; i++) {
            ask("10.0.0.1", "00010" + i);
        }
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThatCode(() -> ask("10.0.0.1", "000199")).doesNotThrowAnyException();

        ask("10.0.0.7", "000555");
        ask("10.0.0.8", "000555");
        ask("10.0.0.9", "000555");
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThatCode(() -> ask("10.0.0.6", "000555")).doesNotThrowAnyException();
    }

    @Test
    void aRefusedCallDoesNotUseUpTheQuota() {
        for (int i = 0; i < 5; i++) {
            ask("10.0.0.1", "00010" + i);
        }
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> ask("10.0.0.1", "000777")).isInstanceOf(ResponseStatusException.class);
        }
        // the refused calls did not count against 000777
        assertThatCode(() -> ask("10.0.0.9", "000777")).doesNotThrowAnyException();
    }
}
