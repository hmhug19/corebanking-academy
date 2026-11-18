package vn.com.coreacademy.assistant.drafting;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentService;

/**
 * Deletes, with its chunks and file, a course source that no drafting session and no old job uses once it is older
 * than {@code app.assistant.generation.unused-source-ttl} (24 hours by default): a chat attachment uploaded for Soạn
 * cùng trợ lý that no session was started from. A source still being read stays for the next run. Logs ids only.
 */
@Slf4j
@Component
public class UnusedSourceCleanup {
    /** Sources deleted per run at most, so one run stays short; the rest go on the next run. */
    static final int BATCH = 200;

    private final CourseDraftingRepository drafting;
    private final KnowledgeDocumentService documents;
    private final GenerationProperties properties;
    private final Clock clock;

    @Autowired
    public UnusedSourceCleanup(CourseDraftingRepository drafting, KnowledgeDocumentService documents,
                               GenerationProperties properties) {
        this(drafting, documents, properties, Clock.systemUTC());
    }

    UnusedSourceCleanup(CourseDraftingRepository drafting, KnowledgeDocumentService documents,
                        GenerationProperties properties, Clock clock) {
        this.drafting = drafting;
        this.documents = documents;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.assistant.generation.unused-source-cleanup-cron:0 15 * * * *}")
    public void run() {
        try {
            int removed = deleteExpired();
            if (removed > 0) {
                log.info("Deleted unused course sources count={}", removed);
            }
        } catch (RuntimeException exception) {
            log.warn("Unused course source cleanup failed type={}", exception.getClass().getName());
        }
    }

    /** Deletes the unused sources older than the lifetime; returns how many were removed. */
    int deleteExpired() {
        OffsetDateTime before = OffsetDateTime.now(clock).minus(properties.unusedSourceTtl());
        List<Long> expired = drafting.unusedSourcesBefore(before, BATCH);
        int removed = 0;
        for (long documentId : expired) {
            if (documents.deleteForSystem(documentId)) {
                removed++;
            }
        }

        return removed;
    }
}
