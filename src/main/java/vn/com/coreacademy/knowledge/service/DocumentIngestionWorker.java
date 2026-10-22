package vn.com.coreacademy.knowledge.service;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.extraction.ExtractionException;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentQueueRepository;
import vn.com.coreacademy.knowledge.repository.KnowledgeDocumentRepository;

/**
 * Background worker for the document queue. It acts on a row and never reads the security context: a job has no
 * implicit user. Log lines carry the document id only, never document text.
 */
@Slf4j
@Component
public class DocumentIngestionWorker {
    private static final String GENERIC_FAILURE =
        "Không xử lý được tài liệu. Vui lòng thử lại hoặc liên hệ quản trị.";

    private final DocumentQueueRepository queue;
    private final KnowledgeDocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final DocumentProcessor processor;
    private final OllamaClient ollama;
    private final TransactionTemplate transaction;
    private final ThreadPoolTaskExecutor executor;
    private final int maxAttempts;
    private final Semaphore freeSlots;

    public DocumentIngestionWorker(DocumentQueueRepository queue, KnowledgeDocumentRepository documents,
                                   DocumentChunkRepository chunks, DocumentProcessor processor, OllamaClient ollama,
                                   TransactionTemplate transaction, ThreadPoolTaskExecutor knowledgeWorkerExecutor,
                                   KnowledgeProperties properties) {
        this.queue = queue;
        this.documents = documents;
        this.chunks = chunks;
        this.processor = processor;
        this.ollama = ollama;
        this.transaction = transaction;
        this.executor = knowledgeWorkerExecutor;
        this.maxAttempts = properties.worker().maxAttempts();
        this.freeSlots = new Semaphore(properties.worker().concurrency());
    }

    /** A previous run that stopped mid-job leaves rows in processing; they go back to the queue. */
    @EventListener(ApplicationReadyEvent.class)
    public void requeueInterrupted() {
        int requeued = queue.requeueInterrupted();
        if (requeued > 0) {
            log.info("Requeued interrupted knowledge documents count={}", requeued);
        }
    }

    /**
     * Every poll interval: while a worker slot is free and Ollama answers, claim queued documents and hand each to the
     * executor. The first run waits one interval so start-up is not slowed down.
     */
    @Scheduled(fixedDelayString = "${app.knowledge.worker.poll-interval}",
        initialDelayString = "${app.knowledge.worker.poll-interval}")
    public void poll() {
        if (freeSlots.availablePermits() == 0 || !ollama.isAvailable()) {
            return;
        }
        while (freeSlots.tryAcquire()) {
            Optional<Long> claimed = claimNext();
            if (claimed.isEmpty()) {
                freeSlots.release();
                return;
            }
            long documentId = claimed.get();
            executor.execute(() -> {
                try {
                    process(documentId);
                } finally {
                    freeSlots.release();
                }
            });
        }
    }

    /** Claims in its own short transaction, so the row lock is released before the long work starts. */
    public Optional<Long> claimNext() {
        return transaction.execute(status -> queue.claimNext());
    }

    /** Runs one claimed document to ready, failed, or back to the queue. */
    public void process(long documentId) {
        Optional<KnowledgeDocument> loaded = documents.findById(documentId);
        if (loaded.isEmpty()) {
            log.warn("Claimed knowledge document is gone documentId={}", documentId);
            return;
        }
        KnowledgeDocument document = loaded.get();

        try {
            DocumentProcessor.Processed result = processor.process(document);
            // One transaction: all chunks and the ready status, so a search never sees half a document.
            transaction.executeWithoutResult(status -> {
                chunks.insertAll(documentId, result.chunks());
                queue.complete(documentId, result.pageCount(), result.chunks().size());
            });
            log.info("Knowledge document ready documentId={} chunks={}", documentId, result.chunks().size());
        } catch (OllamaUnavailableException exception) {
            handleOllamaUnavailable(document);
        } catch (ExtractionException exception) {
            queue.fail(documentId, exception.getMessage());
            log.info("Knowledge document failed documentId={} reason=extraction", documentId);
        } catch (RuntimeException exception) {
            queue.fail(documentId, GENERIC_FAILURE);
            // The message could quote document text; log the type and the stack only.
            log.error("Knowledge document failed documentId={} type={} stack={}", documentId,
                exception.getClass().getName(), Arrays.toString(exception.getStackTrace()));
        }
    }

    private void handleOllamaUnavailable(KnowledgeDocument document) {
        long documentId = document.getId();
        if (document.getAttempts() >= maxAttempts) {
            queue.fail(documentId, "Không kết nối được dịch vụ AI sau " + maxAttempts
                + " lần thử. Vui lòng tải lại tệp sau.");
            log.warn("Knowledge document failed documentId={} reason=ollama-unavailable attempts={}", documentId,
                document.getAttempts());
            return;
        }
        queue.release(documentId);
        log.warn("Knowledge document requeued documentId={} reason=ollama-unavailable attempts={}", documentId,
            document.getAttempts());
    }
}
