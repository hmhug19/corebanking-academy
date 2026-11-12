package vn.com.coreacademy.knowledge.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.common.exception.BusinessException;

/**
 * Fills {@code checksum_sha256} for documents uploaded before the column existed, from the stored file. It runs once
 * in the background after start-up and finds nothing to do on later starts; a document whose file is missing keeps a
 * null checksum and is simply never matched as a duplicate. Logs carry counts only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentChecksumBackfill {
    private record Pending(long id, String storageKey) {
    }

    private final JdbcTemplate jdbc;
    private final KnowledgeFileStore store;
    private final ThreadPoolTaskExecutor knowledgeWorkerExecutor;

    @EventListener(ApplicationReadyEvent.class)
    public void startInBackground() {
        knowledgeWorkerExecutor.execute(() -> {
            try {
                run();
            } catch (RuntimeException exception) {
                log.error("Checksum fill stopped type={}", exception.getClass().getName());
            }
        });
    }

    /** Returns how many documents got a checksum. */
    public int run() {
        List<Pending> pending = jdbc.query("""
            SELECT document_id, storage_key FROM learning_hub.knowledge_documents
             WHERE checksum_sha256 IS NULL ORDER BY document_id
            """, (rs, rowNum) -> new Pending(rs.getLong("document_id"), rs.getString("storage_key")));
        int filled = 0;
        for (Pending item : pending) {
            try {
                String checksum = store.sha256(item.storageKey());
                // The key is part of the match, so a file replaced meanwhile is not given the old file's checksum.
                filled += jdbc.update("""
                    UPDATE learning_hub.knowledge_documents SET checksum_sha256 = ?
                     WHERE document_id = ? AND storage_key = ? AND checksum_sha256 IS NULL
                    """, checksum, item.id(), item.storageKey());
            } catch (RuntimeException exception) {
                if (!(exception instanceof BusinessException)
                    && !(exception.getCause() instanceof java.nio.file.NoSuchFileException)) {
                    log.warn("Checksum not computed documentId={} type={}", item.id(), exception.getClass().getName());
                }
            }
        }
        if (filled > 0) {
            log.info("Checksum filled for older documents count={}", filled);
        }

        return filled;
    }
}
