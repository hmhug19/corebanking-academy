package vn.com.coreacademy.knowledge;

import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.LessonSource;
import vn.com.coreacademy.knowledge.service.ChatPromptBuilder;
import vn.com.coreacademy.knowledge.service.CitationSelector;

import static org.assertj.core.api.Assertions.assertThat;

/** A chunk of a lesson is cited with the lesson's title and link; a document chunk keeps its old shape. */
class LessonCitationTest {
    private static final LessonSource SOURCE = new LessonSource(7, "Tín dụng cơ bản", 70, 3, "Chương 1", 9,
        "Lãi suất", 11, "Nội dung", "/lesson/7/9");

    private static ChunkHit lessonHit(long chunkId) {
        return new ChunkHit(chunkId, 0L, "Tín dụng cơ bản › Lãi suất", null, 0, 0, "Lãi suất là chi phí vay.", 0.9,
            SOURCE);
    }

    @Test
    void lessonCitationCarriesTitleAndLink() {
        List<CitationView> citations = CitationSelector.select("Lãi suất là chi phí vay [1].", List.of(lessonHit(5)));

        assertThat(citations).singleElement().satisfies(citation -> {
            assertThat(citation.documentId()).isZero();
            assertThat(citation.lesson().lessonTitle()).isEqualTo("Lãi suất");
            assertThat(citation.lesson().courseTitle()).isEqualTo("Tín dụng cơ bản");
            assertThat(citation.lesson().url()).isEqualTo("/lesson/7/9");
        });
    }

    @Test
    void documentCitationHasNoLesson() {
        ChunkHit document = new ChunkHit(5, 3L, "quy-che.pdf", 2, 1, 4, "Nội dung quy chế.", 0.8);

        assertThat(CitationSelector.select("Theo quy chế [1].", List.of(document)))
            .singleElement().satisfies(citation -> assertThat(citation.lesson()).isNull());
    }

    @Test
    void chunksOfDifferentKindsWithTheSameIdAreDistinct() {
        ChunkHit document = new ChunkHit(5, 3L, "quy-che.pdf", 2, 1, 4, "Nội dung quy chế.", 0.8);

        assertThat(document.key()).isNotEqualTo(lessonHit(5).key());
    }

    @Test
    void promptNamesCourseChapterAndLesson() {
        String text = ChatPromptBuilder.formatChunk(1, lessonHit(5));

        assertThat(text).contains("Khóa học: Tín dụng cơ bản").contains("chương: Chương 1")
            .contains("bài: Lãi suất").contains("Lãi suất là chi phí vay.");
    }
}
