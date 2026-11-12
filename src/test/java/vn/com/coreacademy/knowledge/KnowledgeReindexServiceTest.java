package vn.com.coreacademy.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.service.KnowledgeReindexService;
import vn.com.coreacademy.knowledge.service.KnowledgeReindexService.State;
import vn.com.coreacademy.knowledge.service.KnowledgeReindexService.Status;
import vn.com.coreacademy.knowledge.service.ReindexSource;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeReindexServiceTest {
    /** A source with ids 1..n whose items record that they ran, and fail when told to. */
    private static final class FakeSource implements ReindexSource {
        final List<Long> ran = new ArrayList<>();
        private final List<Long> ids;
        private final RuntimeException failure;
        private final long failingId;

        FakeSource(List<Long> ids, long failingId, RuntimeException failure) {
            this.ids = ids;
            this.failingId = failingId;
            this.failure = failure;
        }

        @Override
        public String kind() {
            return "fake";
        }

        @Override
        public List<Item> stale() {
            return ids.stream().map(id -> new Item(id, () -> {
                if (id == failingId) {
                    throw failure;
                }
                ran.add(id);
            })).toList();
        }
    }

    @Test
    void reindexesEveryStaleItemAndReportsProgress() {
        FakeSource source = new FakeSource(List.of(1L, 2L, 3L), -1, null);
        KnowledgeReindexService service = new KnowledgeReindexService(List.of(source), Runnable::run);

        Status started = service.start();

        assertThat(source.ran).containsExactly(1L, 2L, 3L);
        assertThat(started.total()).isEqualTo(3);
        Status status = service.status();
        assertThat(status.state()).isEqualTo(State.FINISHED);
        assertThat(status.done()).isEqualTo(3);
        assertThat(status.failed()).isZero();
        assertThat(status.finishedTime()).isNotNull();
    }

    @Test
    void anItemThatFailsIsCountedAndTheOthersStillRun() {
        FakeSource source = new FakeSource(List.of(1L, 2L, 3L), 2L, new IllegalStateException("boom"));
        KnowledgeReindexService service = new KnowledgeReindexService(List.of(source), Runnable::run);

        service.start();

        Status status = service.status();
        assertThat(source.ran).containsExactly(1L, 3L);
        assertThat(status.state()).isEqualTo(State.FINISHED);
        assertThat(status.done()).isEqualTo(2);
        assertThat(status.failed()).isEqualTo(1);
    }

    @Test
    void theRunStopsWhenTheModelIsUnreachable() {
        FakeSource source = new FakeSource(List.of(1L, 2L, 3L), 2L,
            new OllamaUnavailableException("down", new RuntimeException()));
        KnowledgeReindexService service = new KnowledgeReindexService(List.of(source), Runnable::run);

        service.start();

        Status status = service.status();
        assertThat(source.ran).containsExactly(1L);
        assertThat(status.state()).isEqualTo(State.ABORTED);
        assertThat(status.done()).isEqualTo(1);
        assertThat(status.total()).isEqualTo(3);
    }

    @Test
    void nothingStaleFinishesAtOnce() {
        KnowledgeReindexService service = new KnowledgeReindexService(
            List.of(new FakeSource(List.of(), -1, null)), Runnable::run);

        assertThat(service.status().state()).isEqualTo(State.IDLE);
        assertThat(service.start().state()).isEqualTo(State.FINISHED);
    }

    @Test
    void startingWhileARunIsInProgressReturnsItsStatusAndStartsNothingNew() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        ReindexSource blocking = new ReindexSource() {
            @Override
            public String kind() {
                return "blocking";
            }

            @Override
            public List<Item> stale() {
                return List.of(new Item(1, () -> {
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
        };
        List<Runnable> queued = new ArrayList<>();
        Executor executor = queued::add;
        KnowledgeReindexService service = new KnowledgeReindexService(List.of(blocking), executor);

        Status first = service.start();
        Status second = service.start();

        assertThat(first.state()).isEqualTo(State.RUNNING);
        assertThat(second.state()).isEqualTo(State.RUNNING);
        assertThat(queued).hasSize(1);
        release.countDown();
        queued.get(0).run();
        assertThat(service.status().state()).isEqualTo(State.FINISHED);
    }
}
