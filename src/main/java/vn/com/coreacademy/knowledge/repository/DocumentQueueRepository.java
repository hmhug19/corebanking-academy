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

    // A replacement file rides on a ready document; the document keeps answering with the old version meanwhile.
    private static final String CLAIM_REPLACEMENT = """
        UPDATE learning_hub.knowledge_documents d
           SET replacement_status = 'processing', attempts = d.attempts + 1
         WHERE d.document_id = (SELECT document_id FROM learning_hub.knowledge_documents
                                 WHERE replacement_status = 'queued' AND status = 'ready'
                                 ORDER BY create_time
                                 FOR UPDATE SKIP LOCKED
                                 LIMIT 1)
        RETURNING d.document_id
        """;
    private static final String REQUEST_REPLACEMENT = """
        UPDATE learning_hub.knowledge_documents
           SET replacement_status = 'queued', replacement_error = NULL, replacement_storage_key = ?,
               replacement_file_name = ?, replacement_mime_type = ?, replacement_size_bytes = ?,
               replacement_checksum = ?, attempts = 0
         WHERE document_id = ? AND status = 'ready'
           AND (replacement_status IS NULL OR replacement_status = 'failed')
        """;
    // The new file becomes the document's file in the same statement that clears the pending columns.
    private static final String COMPLETE_REPLACEMENT = """
        UPDATE learning_hub.knowledge_documents
           SET file_name = replacement_file_name, mime_type = replacement_mime_type,
               size_bytes = replacement_size_bytes, checksum_sha256 = replacement_checksum,
               storage_key = replacement_storage_key, page_count = ?, chunk_count = ?, processed_at = now(),
               error_message = NULL, replacement_status = NULL, replacement_error = NULL,
               replacement_storage_key = NULL, replacement_file_name = NULL, replacement_mime_type = NULL,
               replacement_size_bytes = NULL, replacement_checksum = NULL
         WHERE document_id = ? AND status = 'ready' AND replacement_status = 'processing'
        """;
    private static final String FAIL_REPLACEMENT = """
        UPDATE learning_hub.knowledge_documents
           SET replacement_status = 'failed', replacement_error = ?, replacement_storage_key = NULL,
               replacement_mime_type = NULL, replacement_size_bytes = NULL, replacement_checksum = NULL
         WHERE document_id = ? AND replacement_status = 'processing'
        """;
    private static final String RELEASE_REPLACEMENT = """
        UPDATE learning_hub.knowledge_documents
           SET replacement_status = 'queued'
         WHERE document_id = ? AND replacement_status = 'processing'
        """;
    private static final String REQUEUE_INTERRUPTED_REPLACEMENTS = """
        UPDATE learning_hub.knowledge_documents
           SET replacement_status = 'queued'
         WHERE replacement_status = 'processing'
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
        return jdbc.update(REQUEUE_INTERRUPTED) + jdbc.update(REQUEUE_INTERRUPTED_REPLACEMENTS);
    }

    /** Takes the oldest queued replacement of a ready document and marks it processing; empty when none waits. */
    public Optional<Long> claimNextReplacement() {
        return jdbc.queryForList(CLAIM_REPLACEMENT, Long.class).stream().findFirst();
    }

    /** Queues a stored file as the replacement of a ready document; false when it is not ready or already replacing. */
    public boolean requestReplacement(long documentId, String storageKey, String fileName, String mimeType,
                                      long sizeBytes, String checksum) {
        return jdbc.update(REQUEST_REPLACEMENT, storageKey, fileName, mimeType, sizeBytes, checksum, documentId) == 1;
    }

    /**
     * Makes the pending file the document's file; false (and nothing changed) when the document is gone or no longer
     * has a replacement in progress. Run it first in the transaction that swaps the chunks: the row lock it takes keeps
     * a delete or a re-index from interleaving with the swap.
     */
    public boolean completeReplacement(long documentId, Integer pageCount, int chunkCount) {
        return jdbc.update(COMPLETE_REPLACEMENT, pageCount, chunkCount, documentId) == 1;
    }

    /** The replacement could not be processed; the document keeps its old version and shows the reason. */
    public void failReplacement(long documentId, String errorMessage) {
        jdbc.update(FAIL_REPLACEMENT, errorMessage, documentId);
    }

    /** Back to the queue for a later attempt (the model was unreachable). */
    public void releaseReplacement(long documentId) {
        jdbc.update(RELEASE_REPLACEMENT, documentId);
    }
}
