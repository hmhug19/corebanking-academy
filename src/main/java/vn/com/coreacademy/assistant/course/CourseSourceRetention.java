package vn.com.coreacademy.assistant.course;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.course.CourseJobRepository.ExpiredSource;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentService;

/**
 * Deletes the source file of a course generation job, with its chunks, once the job has been over for
 * {@code app.assistant.generation.source-retention} (30 days by default): finished, stopped or failed for the last
 * time. After that, writing a lesson again needs the file attached again. Logs ids only.
 */
@Slf4j
@Component
public class CourseSourceRetention {
    private final CourseJobRepository jobs;
    private final KnowledgeDocumentService documents;
    private final GenerationProperties properties;
    private final Clock clock;

    @Autowired
    public CourseSourceRetention(CourseJobRepository jobs, KnowledgeDocumentService documents,
                                 GenerationProperties properties) {
        this(jobs, documents, properties, Clock.systemUTC());
    }

    CourseSourceRetention(CourseJobRepository jobs, KnowledgeDocumentService documents,
                          GenerationProperties properties, Clock clock) {
        this.jobs = jobs;
        this.documents = documents;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.assistant.generation.source-cleanup-cron:0 30 3 * * *}")
    public void run() {
        try {
            int removed = deleteExpired();
            if (removed > 0) {
                log.info("Deleted expired course sources count={}", removed);
            }
        } catch (RuntimeException exception) {
            log.warn("Course source cleanup failed type={}", exception.getClass().getName());
        }
    }

    /** Deletes the sources of jobs that ended before the retention period; returns how many were removed. */
    int deleteExpired() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(properties.sourceRetention());
        List<ExpiredSource> expired = jobs.expiredSources(cutoff);
        int removed = 0;
        for (ExpiredSource source : expired) {
            if (documents.deleteForSystem(source.documentId())) {
                jobs.clearSource(source.jobId());
                removed++;
            }
        }

        return removed;
    }
}
