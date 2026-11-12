package vn.com.coreacademy.knowledge.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;

/**
 * Re-indexes, in the background, every document and course revision whose chunker version or embed model differs from
 * the configured one. One run at a time; starting while one runs just returns its status. Each item swaps its chunks
 * in one transaction (see {@link ReindexSource}), so the old chunks answer until the new ones commit. Progress is
 * kept in memory: a restart forgets it, and a new run finds what is still stale. Logs carry ids and counts only.
 */
@Slf4j
@Service
public class KnowledgeReindexService {
    public enum State { IDLE, RUNNING, FINISHED, ABORTED }

    /** Progress of the latest run; {@code failed} items stay stale and are picked up by the next run. */
    public record Status(State state, int total, int done, int failed, Instant startedTime, Instant finishedTime) {
    }

    private final List<ReindexSource> sources;
    private final Executor executor;
    private final ExecutorService ownExecutor;
    private Status status = new Status(State.IDLE, 0, 0, 0, null, null);

    @Autowired
    public KnowledgeReindexService(List<ReindexSource> sources) {
        this.sources = sources;
        this.ownExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kb-reindex");
            thread.setDaemon(true);
            return thread;
        });
        this.executor = ownExecutor;
    }

    /** For tests and callers that bring their own executor. */
    public KnowledgeReindexService(List<ReindexSource> sources, Executor executor) {
        this.sources = sources;
        this.executor = executor;
        this.ownExecutor = null;
    }

    @PreDestroy
    void shutdown() {
        if (ownExecutor != null) {
            ownExecutor.shutdownNow();
        }
    }

    public synchronized Status status() {
        return status;
    }

    /** Starts a run over what is stale now, or returns the status of the run in progress. */
    public Status start() {
        List<ReindexSource.Item> items = new ArrayList<>();
        synchronized (this) {
            if (status.state() == State.RUNNING) {
                return status;
            }
            for (ReindexSource source : sources) {
                items.addAll(source.stale());
            }
            Instant now = Instant.now();
            if (items.isEmpty()) {
                status = new Status(State.FINISHED, 0, 0, 0, now, now);
                return status;
            }
            status = new Status(State.RUNNING, items.size(), 0, 0, now, null);
        }
        log.info("Re-index started items={}", items.size());
        executor.execute(() -> run(items));

        return status();
    }

    private void run(List<ReindexSource.Item> items) {
        int done = 0;
        int failed = 0;
        State end = State.FINISHED;
        for (ReindexSource.Item item : items) {
            try {
                item.run().run();
                done++;
            } catch (OllamaUnavailableException exception) {
                log.warn("Re-index stopped: Ollama unavailable id={}", item.id());
                end = State.ABORTED;
                break;
            } catch (RuntimeException exception) {
                // The message could quote document text; the type is enough to find the cause.
                failed++;
                log.error("Re-index failed id={} type={}", item.id(), exception.getClass().getName());
            }
            progress(items.size(), done, failed);
        }
        synchronized (this) {
            status = new Status(end, items.size(), done, failed, status.startedTime(), Instant.now());
        }
        log.info("Re-index {} done={} failed={}", end, done, failed);
    }

    private synchronized void progress(int total, int done, int failed) {
        status = new Status(State.RUNNING, total, done, failed, status.startedTime(), null);
    }
}
