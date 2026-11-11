package vn.com.coreacademy.assistant;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.agent.TurnLimiter.Admission;
import vn.com.coreacademy.assistant.config.AssistantLimits;
import vn.com.coreacademy.assistant.config.AssistantProperties;

import static org.assertj.core.api.Assertions.assertThat;

class TurnLimiterTest {
    private final MutableClock clock = new MutableClock();
    private final TurnLimiter limiter = limiter(60);

    private TurnLimiter limiter(int questionsPerHour) {
        return new TurnLimiter(new AssistantProperties(4, 20, Duration.ofMinutes(10), 2),
            new AssistantLimits(questionsPerHour), clock);
    }

    private static TurnLimiter.Turn started(Admission admission) {
        assertThat(admission).isInstanceOf(Admission.Started.class);
        return ((Admission.Started) admission).turn();
    }

    @Test
    void aUserHasOneTurnRunningAtATime() {
        TurnLimiter.Turn first = started(limiter.admit("000004"));

        assertThat(limiter.admit("000004")).isInstanceOf(Admission.Busy.class);
        first.close();
        assertThat(limiter.admit("000004")).isInstanceOf(Admission.Started.class);
    }

    @Test
    void cancellingARunningTurnAbortsItsModelCallAndFreesTheUserAtOnce() {
        TurnLimiter.Turn first = started(limiter.admit("000004"));
        first.bind(7);

        assertThat(limiter.cancel("000004", 7)).isTrue();

        assertThat(first.cancellation().isCancelled()).isTrue();
        assertThat(limiter.admit("000004")).isInstanceOf(Admission.Started.class);
    }

    @Test
    void aCancelNamesTheUsersOwnTurnOfThatConversationOnly() {
        TurnLimiter.Turn first = started(limiter.admit("000004"));
        first.bind(7);

        assertThat(limiter.cancel("000005", 7)).isFalse();
        assertThat(limiter.cancel("000004", 8)).isFalse();
        assertThat(first.cancellation().isCancelled()).isFalse();
        assertThat(limiter.admit("000004")).isInstanceOf(Admission.Busy.class);
    }

    @Test
    void theServerRunsAtMostTheConfiguredNumberOfTurnsAndARefusalHoldsNothing() {
        TurnLimiter.Turn first = started(limiter.admit("000002"));
        started(limiter.admit("000003"));

        assertThat(limiter.admit("000004")).isInstanceOf(Admission.Busy.class);
        first.close();
        assertThat(limiter.admit("000004")).isInstanceOf(Admission.Started.class);
    }

    @Test
    void closingATurnTwiceReleasesItOnce() {
        TurnLimiter.Turn first = started(limiter.admit("000002"));
        first.close();
        first.close();
        started(limiter.admit("000003"));
        started(limiter.admit("000004"));

        assertThat(limiter.admit("000005")).isInstanceOf(Admission.Busy.class);
    }

    @Test
    void theQuestionAfterTheHourlyLimitIsRefusedWithTheWaitUntilTheOldestLeavesTheWindow() {
        TurnLimiter small = limiter(3);
        for (int i = 0; i < 3; i++) {
            started(small.admit("000004")).close();
            clock.advance(Duration.ofMinutes(1));
        }

        Admission refused = small.admit("000004");

        assertThat(refused).isInstanceOf(Admission.RateLimited.class);
        Admission.RateLimited limited = (Admission.RateLimited) refused;
        assertThat(limited.retryAfter()).isEqualTo(Duration.ofMinutes(57));
        assertThat(limited.message()).isEqualTo("Bạn đã hỏi nhiều, thử lại sau 57 phút.");
    }

    @Test
    void theSixtyFirstQuestionWithinAnHourIsRefusedByDefault() {
        for (int i = 0; i < 60; i++) {
            started(limiter.admit("000004")).close();
        }

        assertThat(limiter.admit("000004")).isInstanceOf(Admission.RateLimited.class);
    }

    @Test
    void theWindowRollsSoAnOldQuestionStopsCounting() {
        TurnLimiter small = limiter(2);
        started(small.admit("000004")).close();
        clock.advance(Duration.ofMinutes(30));
        started(small.admit("000004")).close();
        assertThat(small.admit("000004")).isInstanceOf(Admission.RateLimited.class);

        clock.advance(Duration.ofMinutes(31));

        assertThat(small.admit("000004")).isInstanceOf(Admission.Started.class);
    }

    @Test
    void theWaitIsRoundedUpToWholeMinutesAndIsNeverZero() {
        assertThat(new Admission.RateLimited(Duration.ofSeconds(1)).message()).endsWith("sau 1 phút.");
        assertThat(new Admission.RateLimited(Duration.ofSeconds(61)).message()).endsWith("sau 2 phút.");
    }

    @Test
    void aRefusalCostsNoQuestionAndHoldsNoPlace() {
        TurnLimiter small = limiter(1);
        started(small.admit("000004")).close();

        assertThat(small.admit("000004")).isInstanceOf(Admission.RateLimited.class);
        // Two places exist; the refused user holds none, so two other users can still start.
        assertThat(small.admit("000005")).isInstanceOf(Admission.Started.class);
        assertThat(small.admit("000006")).isInstanceOf(Admission.Started.class);
    }
}
