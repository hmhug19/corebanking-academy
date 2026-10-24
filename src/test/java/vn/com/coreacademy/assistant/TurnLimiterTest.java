package vn.com.coreacademy.assistant;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.config.AssistantProperties;

import static org.assertj.core.api.Assertions.assertThat;

class TurnLimiterTest {
    private final TurnLimiter limiter = new TurnLimiter(new AssistantProperties(4, 20, Duration.ofMinutes(10), 2));

    @Test
    void aUserHasOneTurnRunningAtATime() {
        Optional<TurnLimiter.Turn> first = limiter.tryStart("000004");

        assertThat(first).isPresent();
        assertThat(limiter.tryStart("000004")).isEmpty();
        first.get().close();
        assertThat(limiter.tryStart("000004")).isPresent();
    }

    @Test
    void theServerRunsAtMostTheConfiguredNumberOfTurnsAndARefusalHoldsNothing() {
        TurnLimiter.Turn first = limiter.tryStart("000002").orElseThrow();
        limiter.tryStart("000003").orElseThrow();

        assertThat(limiter.tryStart("000004")).isEmpty();
        first.close();
        assertThat(limiter.tryStart("000004")).isPresent();
    }

    @Test
    void closingATurnTwiceReleasesItOnce() {
        TurnLimiter.Turn first = limiter.tryStart("000002").orElseThrow();
        first.close();
        first.close();
        limiter.tryStart("000003").orElseThrow();
        limiter.tryStart("000004").orElseThrow();

        assertThat(limiter.tryStart("000005")).isEmpty();
    }
}
