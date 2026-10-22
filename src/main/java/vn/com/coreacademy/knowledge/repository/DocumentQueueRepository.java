package vn.com.coreacademy.knowledge.repository;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The state machine of knowledge_documents as a job queue: queued → processing → ready | failed. All transitions are
 * single statements guarded by the expected current status, so a stray call can never overwrite a finished document.
 */
@Repository
@RequiredArgsConstructor
public class DocumentQueueRepository {
    // SKIP LOCKED lets several workers (or instances) claim different rows without waiting on each other.
    private static final String CLAIM = """
        UPDATE learning_hub.knowledge_documents d
           SET status = 'processing', started_at = now(), attempts = d.attempts + 1
         WHERE d.document_id = (SELECT document_id FROM learning_hub.knowledge_documents
                                 WHERE status = 'queued'
                                 ORDER BY create_time
                                 FOR UPDATE SKIP LOCKED
                                 LIMIT 1)
        RETURNING d.document_id
        """;
    private static final String COMPLETE = """
        UPDATE learning_hub.knowledge_documents
           SET status = 'ready', error_message = NULL, page_count = ?, chunk_count = ?, processed_at = now()
         WHERE document_id = ? AND status = 'processing'
        """;
    private static final String FAIL = """
        UPDATE learning_hub.knowledge_documents
           SET status = 'failed', error_message = ?, processed_at = now()
         WHERE document_id = ? AND status = 'processing'
        """;
    private static final String RELEASE = """
        UPDATE learning_hub.knowledge_documents
           SET status = 'queued'
         WHERE document_id = ? AND status = 'processing'
        """;
    private static final String REQUEUE_INTERRUPTED = """
        UPDATE learning_hub.knowledge_documents
           SET status = 'queued'
         WHERE status = 'processing'
        """;

    private final JdbcTemplate jdbc;

    /** Takes the oldest queued document and marks it processing; empty when nothing is waiting. */
    public Optional<Long> claimNext() {
        List<Long> claimed = jdbc.queryForList(CLAIM, Long.class);
        return claimed.stream().findFirst();
    }

    /** Must run in the transaction that inserts the chunks, so a search never sees half a document. */
    public void complete(long documentId, Integer pageCount, int chunkCount) {
        jdbc.update(COMPLETE, pageCount, chunkCount, documentId);
    }

    public void fail(long documentId, String errorMessage) {
        jdbc.update(FAIL, errorMessage, documentId);
    }

    /** Back to the queue for a later attempt (the model was unreachable). */
    public void release(long documentId) {
        jdbc.update(RELEASE, documentId);
    }

    /** Rows left in processing by a previous run that stopped mid-job; returns how many went back to the queue. */
    public int requeueInterrupted() {
        return jdbc.update(REQUEUE_INTERRUPTED);
    }
}
