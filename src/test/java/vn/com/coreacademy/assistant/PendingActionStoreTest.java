package vn.com.coreacademy.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.action.PendingAction;
import vn.com.coreacademy.assistant.action.PendingActionStore;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.dto.PendingActionView.Change;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingActionStoreTest {
    private static final Duration TTL = Duration.ofMinutes(10);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-24T09:00:00Z"));
    private final PendingActionStore store =
        new PendingActionStore(new AssistantProperties(4, 20, TTL, 4), clock);

    private PendingAction add(String userId) {
        return store.add(userId, "update_profile", new ObjectMapper().createObjectNode().put("positionName", "Mới"),
            "Cập nhật hồ sơ cá nhân", List.of(new Change("positionName", "Chức danh", "Cũ", "Mới")));
    }

    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getResponseCode()).isEqualTo(AssistantResponseCode.ACTION_NOT_FOUND));
    }

    @Test
    void anAddedActionHasAnIdAndExpiresAfterTheTtl() {
        PendingAction action = add("000004");

        assertThat(action.id()).isNotBlank();
        assertThat(action.userId()).isEqualTo("000004");
        assertThat(action.createdAt()).isEqualTo(Instant.parse("2026-10-24T09:00:00Z"));
        assertThat(action.expiresAt()).isEqualTo(Instant.parse("2026-10-24T09:10:00Z"));
        assertThat(action.view().expiresAt()).isEqualTo(action.expiresAt());
    }

    @Test
    void theOwnerTakesAnActionOnceAndASecondTakeIsNotFound() {
        PendingAction action = add("000004");

        assertThat(store.take(action.id(), "000004")).isEqualTo(action);
        assertNotFound(() -> store.take(action.id(), "000004"));
    }

    @Test
    void anotherUsersIdIsNotFoundAndLeavesTheActionToItsOwner() {
        PendingAction action = add("000004");

        assertNotFound(() -> store.take(action.id(), "000002"));
        assertNotFound(() -> store.cancel(action.id(), "000002"));
        assertThat(store.take(action.id(), "000004")).isEqualTo(action);
    }

    @Test
    void anUnknownIdIsNotFound() {
        assertNotFound(() -> store.take("no-such-id", "000004"));
        assertNotFound(() -> store.cancel("no-such-id", "000004"));
    }

    @Test
    void anExpiredActionIsNotFoundAndIsRemoved() {
        PendingAction action = add("000004");
        clock.advance(TTL.plusSeconds(1));

        assertNotFound(() -> store.take(action.id(), "000004"));
        assertThat(store.size()).isZero();
    }

    @Test
    void aCancelledActionCannotBeTakenAndCancelTwiceIsNotFound() {
        PendingAction action = add("000004");

        store.cancel(action.id(), "000004");

        assertNotFound(() -> store.take(action.id(), "000004"));
        assertNotFound(() -> store.cancel(action.id(), "000004"));
    }

    @Test
    void takeSucceedsForExactlyOneOfManyConcurrentCallers() throws Exception {
        PendingAction action = add("000004");
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            tasks.add(() -> {
                go.await();
                try {
                    store.take(action.id(), "000004");
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            });
        }
        List<Future<Boolean>> results = new ArrayList<>();
        tasks.forEach(task -> results.add(pool.submit(task)));
        go.countDown();

        long taken = 0;
        for (Future<Boolean> result : results) {
            taken += result.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(taken).isEqualTo(1);
    }

    @Test
    void theTwentyFirstActionOfAUserDropsTheOldest() {
        List<PendingAction> actions = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            actions.add(add("000004"));
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(store.size()).isEqualTo(20);
        assertNotFound(() -> store.take(actions.get(0).id(), "000004"));
        assertThat(store.take(actions.get(1).id(), "000004")).isEqualTo(actions.get(1));
    }

    @Test
    void theCapIsPerUser() {
        PendingAction other = add("000002");
        for (int i = 0; i < 21; i++) {
            add("000004");
        }

        assertThat(store.take(other.id(), "000002")).isEqualTo(other);
    }

    @Test
    void theSweepRemovesOnlyExpiredEntries() {
        PendingAction old = add("000004");
        clock.advance(Duration.ofMinutes(6));
        PendingAction fresh = add("000004");
        clock.advance(Duration.ofMinutes(5));

        store.sweep();

        assertThat(store.size()).isEqualTo(1);
        assertNotFound(() -> store.take(old.id(), "000004"));
        assertThat(store.take(fresh.id(), "000004")).isEqualTo(fresh);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
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
}
