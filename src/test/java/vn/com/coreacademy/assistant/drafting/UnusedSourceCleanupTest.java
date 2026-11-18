package vn.com.coreacademy.assistant.drafting;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Chat attachments no session was started from are deleted once older than unused-source-ttl. */
class UnusedSourceCleanupTest {
    private static final Instant NOW = Instant.parse("2026-12-01T03:15:00Z");

    private final CourseDraftingRepository drafting = mock(CourseDraftingRepository.class);
    private final KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);

    private UnusedSourceCleanup cleanup(Duration ttl) {
        return new UnusedSourceCleanup(drafting, documents, new GenerationProperties(3, Duration.ofSeconds(3),
            Duration.ofDays(30), Duration.ofSeconds(90), 10, ttl), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unusedSourcesOlderThanTheLifetimeAreDeleted() {
        OffsetDateTime before = OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(24)), ZoneOffset.UTC);
        when(drafting.unusedSourcesBefore(before, UnusedSourceCleanup.BATCH)).thenReturn(List.of(70L, 80L));
        when(documents.deleteForSystem(70)).thenReturn(true);
        when(documents.deleteForSystem(80)).thenReturn(true);

        assertThat(cleanup(Duration.ofHours(24)).deleteExpired()).isEqualTo(2);
        verify(documents).deleteForSystem(70);
        verify(documents).deleteForSystem(80);
    }

    @Test
    void aSourceStillBeingReadStaysForTheNextRun() {
        when(drafting.unusedSourcesBefore(any(), anyInt())).thenReturn(List.of(70L));
        when(documents.deleteForSystem(70)).thenReturn(false);

        assertThat(cleanup(Duration.ofHours(24)).deleteExpired()).isZero();
    }

    @Test
    void theLifetimeComesFromTheProperty() {
        cleanup(Duration.ofHours(6)).deleteExpired();

        verify(drafting).unusedSourcesBefore(OffsetDateTime.ofInstant(NOW.minus(Duration.ofHours(6)), ZoneOffset.UTC),
            UnusedSourceCleanup.BATCH);
    }

    @Test
    void theDefaultsAreTenUploadsADayAndOneDayOfLife() {
        GenerationProperties defaults = new GenerationProperties(3, Duration.ofSeconds(3));

        assertThat(defaults.sourceUploadsPerDay()).isEqualTo(10);
        assertThat(defaults.unusedSourceTtl()).isEqualTo(Duration.ofHours(24));
    }
}
