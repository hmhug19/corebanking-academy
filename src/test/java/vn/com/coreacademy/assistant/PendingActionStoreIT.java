package vn.com.coreacademy.assistant;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.coreacademy.assistant.action.PendingAction;
import vn.com.coreacademy.assistant.action.PendingActionRepository;
import vn.com.coreacademy.assistant.action.PendingActionStore;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.dto.PendingActionView.Change;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pending actions against the real table: they survive a "restart" and are answered once, whoever races. */
class PendingActionStoreIT extends KnowledgeIntegrationTest {
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String ME = "000004";
    private static final String THEM = "000002";

    @Autowired
    private PendingActionRepository repository;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private final MutableClock clock = new MutableClock();
    private PendingActionStore store;

    private PendingActionStore storeOn(MutableClock clock) {
        return new PendingActionStore(new AssistantProperties(4, 20, TTL, 4), repository, json, clock);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM learning_hub.assistant_pending_actions");
        store = storeOn(clock);
    }

    private PendingAction add(String userId) {
        return store.add(userId, "update_profile", json.createObjectNode().put("positionName", "Mới"),
            "Cập nhật hồ sơ cá nhân", List.of(new Change("positionName", "Chức danh", "Cũ", "Mới")));
    }

    private void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getResponseCode()).isEqualTo(AssistantResponseCode.ACTION_NOT_FOUND));
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT status FROM learning_hub.assistant_pending_actions WHERE action_id = ?",
            String.class, id);
    }

    @Test
    void anActionAddedBeforeARestartCanBeConfirmedAfterIt() {
        PendingAction action = add(ME);

        PendingActionStore afterRestart = storeOn(clock);

        assertThat(afterRestart.take(action.id(), ME)).isEqualTo(action);
    }

    @Test
    void theOwnerTakesAnActionOnceAndASecondTakeIsNotFound() {
        PendingAction action = add(ME);

        assertThat(store.take(action.id(), ME)).isEqualTo(action);
        assertNotFound(() -> store.take(action.id(), ME));
        assertThat(status(action.id())).isEqualTo("CONFIRMED");
    }

    @Test
    void foreignExpiredAndUnknownIdsAllFailTheSameWayAndAForeignOneStaysWithItsOwner() {
        PendingAction action = add(ME);
        PendingAction stale = add(ME);

        assertNotFound(() -> store.take(action.id(), THEM));
        assertNotFound(() -> store.cancel(action.id(), THEM));
        assertNotFound(() -> store.take("no-such-id", ME));
        assertThat(status(action.id())).isEqualTo("PENDING");

        clock.advance(TTL.plusSeconds(1));
        assertNotFound(() -> store.take(stale.id(), ME));
        assertThat(status(stale.id())).isEqualTo("PENDING");
    }

    @Test
    void aCancelledActionCannotBeTakenAndCancelTwiceIsNotFound() {
        PendingAction action = add(ME);

        store.cancel(action.id(), ME);

        assertNotFound(() -> store.take(action.id(), ME));
        assertNotFound(() -> store.cancel(action.id(), ME));
        assertThat(status(action.id())).isEqualTo("CANCELLED");
    }

    @Test
    void theOutcomeIsRecordedOnTheRow() {
        PendingAction done = add(ME);
        PendingAction refused = add(ME);

        store.take(done.id(), ME);
        store.succeeded(done.id(), "Đã cập nhật hồ sơ.");
        store.take(refused.id(), ME);
        store.failed(refused.id(), "Dữ liệu đã thay đổi.");

        assertThat(status(done.id())).isEqualTo("CONFIRMED");
        assertThat(repository.findById(done.id()).orElseThrow().getResultMessage()).isEqualTo("Đã cập nhật hồ sơ.");
        assertThat(status(refused.id())).isEqualTo("FAILED");
        assertThat(repository.findById(refused.id()).orElseThrow().getResultMessage())
            .isEqualTo("Dữ liệu đã thay đổi.");
    }

    @Test
    void takeSucceedsForExactlyOneOfManyConcurrentCallers() throws Exception {
        PendingAction action = add(ME);
        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Callable<Boolean> task = () -> {
                go.await();
                try {
                    // Each caller is its own "instance": a store of its own over the same table.
                    storeOn(clock).take(action.id(), ME);
                    return true;
                } catch (BusinessException exception) {
                    return false;
                }
            };
            results.add(pool.submit(task));
        }
        go.countDown();

        long taken = 0;
        for (Future<Boolean> result : results) {
            taken += result.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(taken).isEqualTo(1);
    }

    @Test
    void theTwentyFirstLiveActionOfAUserCancelsTheOldestAndTheCapIsPerUser() {
        PendingAction other = add(THEM);
        List<PendingAction> actions = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            actions.add(add(ME));
        }

        assertNotFound(() -> store.take(actions.get(0).id(), ME));
        assertThat(status(actions.get(0).id())).isEqualTo("CANCELLED");
        assertThat(store.take(actions.get(1).id(), ME)).isEqualTo(actions.get(1));
        assertThat(store.take(other.id(), THEM)).isEqualTo(other);
    }
}
