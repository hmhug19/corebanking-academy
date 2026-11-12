package vn.com.coreacademy.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.course.CourseJobRepository;
import vn.com.coreacademy.assistant.course.CourseJobRepository.ExpiredSource;
import vn.com.coreacademy.assistant.course.CourseSourceRetention;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseSourceRetentionTest {
    private static final Instant NOW = Instant.parse("2026-12-01T03:30:00Z");

    private final CourseJobRepository jobs = mock(CourseJobRepository.class);
    private final KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);

    private CourseSourceRetention retention(Duration period) throws ReflectiveOperationException {
        var constructor = CourseSourceRetention.class.getDeclaredConstructor(CourseJobRepository.class,
            KnowledgeDocumentService.class, GenerationProperties.class, Clock.class);
        constructor.setAccessible(true);
        return constructor.newInstance(jobs, documents, new GenerationProperties(3, Duration.ofSeconds(3), period),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void sourcesOfJobsThatEndedBeforeTheRetentionPeriodAreDeletedAndDetachedFromTheJob() throws Exception {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(30)), ZoneOffset.UTC);
        when(jobs.expiredSources(cutoff)).thenReturn(List.of(new ExpiredSource(7, 70), new ExpiredSource(8, 80)));
        when(documents.deleteForSystem(70)).thenReturn(true);
        when(documents.deleteForSystem(80)).thenReturn(true);

        int removed = retentionOf(Duration.ofDays(30));

        assertThat(removed).isEqualTo(2);
        verify(jobs).clearSource(7);
        verify(jobs).clearSource(8);
    }

    @Test
    void aSourceBeingProcessedStaysForTheNextRun() throws Exception {
        when(jobs.expiredSources(any())).thenReturn(List.of(new ExpiredSource(7, 70)));
        when(documents.deleteForSystem(70)).thenReturn(false);

        assertThat(retentionOf(Duration.ofDays(30))).isZero();
        verify(jobs, never()).clearSource(7);
    }

    @Test
    void theRetentionPeriodIsAProperty() throws Exception {
        retentionOf(Duration.ofDays(7));

        verify(jobs).expiredSources(OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(7)), ZoneOffset.UTC));
    }

    @Test
    void theDefaultPeriodIsThirtyDays() {
        assertThat(new GenerationProperties(3, Duration.ofSeconds(3)).sourceRetention()).isEqualTo(Duration.ofDays(30));
    }

    private int retentionOf(Duration period) throws Exception {
        var method = CourseSourceRetention.class.getDeclaredMethod("deleteExpired");
        method.setAccessible(true);
        return (int) method.invoke(retention(period));
    }
}
