package vn.com.coreacademy.learning;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import vn.com.coreacademy.knowledge.chunking.Chunker;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.SourceBlock;
import vn.com.coreacademy.learning.index.CourseContentRetrieval;
import vn.com.coreacademy.learning.index.LessonReader;
import vn.com.coreacademy.learning.index.LessonReader.LessonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LessonReaderTest {
    private final CourseContentRetrieval retrieval = mock(CourseContentRetrieval.class);
    private final CourseContentChunkRepository index = mock(CourseContentChunkRepository.class);
    private final Chunker chunker = new Chunker(new KnowledgeProperties(Path.of("unused"), DataSize.ofMegabytes(20),
        new KnowledgeProperties.Worker(Duration.ofSeconds(2), 1, 3), new KnowledgeProperties.Chunk(16, 40, 0),
        new KnowledgeProperties.Retrieval(5, 0.35), new KnowledgeProperties.Ollama("http://localhost:11434", "chat",
        "embed", Duration.ofSeconds(5), Duration.ofSeconds(180), Duration.ofMinutes(10))));
    private final LessonReader reader = new LessonReader(retrieval, index, chunker);

    private static SourceBlock text(long id, String body) {
        return new SourceBlock(1, "Chương 1", 88, "Bài 1", id, "Khối " + id, "text", body, "markdown", null, null);
    }

    private static SourceBlock video(long id) {
        return new SourceBlock(1, "Chương 1", 71, "Bài video", id, "Video", "video", null, null, null, null);
    }

    @Test
    void anUnindexedLessonIsReadFromItsBlocksAndCitesTheLessonNotABlock() {
        when(retrieval.lessonChunks(88, LessonReader.MAX_CHUNKS_PLUS_ONE)).thenReturn(List.of());
        when(index.lessonBlocks(3, 88)).thenReturn(List.of(text(1, "# Lãi suất\nLãi suất là chi phí vay vốn.")));

        LessonText read = reader.read(26, "Tín dụng", 3, 88, "Bài 1");

        assertThat(read.noText()).isFalse();
        assertThat(read.truncated()).isFalse();
        assertThat(read.hits()).hasSize(1);
        assertThat(read.hits().get(0).content()).contains("Lãi suất là chi phí vay vốn.");
        assertThat(read.hits().get(0).lesson().url()).isEqualTo("/lesson/26/88");
        assertThat(read.hits().get(0).lesson().blockId()).isZero();
        assertThat(read.hits().get(0).chunkId()).isNegative();
    }

    @Test
    void aLessonWithNoTextAtAllSaysSo() {
        when(retrieval.lessonChunks(71, LessonReader.MAX_CHUNKS_PLUS_ONE)).thenReturn(List.of());
        when(index.lessonBlocks(3, 71)).thenReturn(List.of(video(5)));

        LessonText read = reader.read(25, "Khóa video", 3, 71, "Bài video");

        assertThat(read.noText()).isTrue();
        assertThat(read.hits()).isEmpty();
    }

    @Test
    void aLessonWhoseOnlyBlockIsAVideoWithAnUntimedTranscriptHasText() {
        var lines = JsonNodeFactory.instance.arrayNode();
        lines.addObject().putNull("startSeconds").put("text", "Xin chào các bạn.");
        lines.addObject().putNull("startSeconds").put("text", "Hôm nay học về lãi suất.");
        when(retrieval.lessonChunks(71, LessonReader.MAX_CHUNKS_PLUS_ONE)).thenReturn(List.of());
        when(index.lessonBlocks(3, 71)).thenReturn(List.of(new SourceBlock(1, "Chương 1", 71, "Bài video", 5, "Video",
            "video", null, null, null, lines)));

        LessonText read = reader.read(25, "Khóa video", 3, 71, "Bài video");

        assertThat(read.noText()).isFalse();
        assertThat(read.hits()).isNotEmpty();
        assertThat(read.hits().get(0).content()).contains("Xin chào các bạn.").contains("Hôm nay học về lãi suất.");
    }

    @Test
    void aLongLessonIsReadUpToTheCapAndSaysItWasCut() {
        when(retrieval.lessonChunks(88, LessonReader.MAX_CHUNKS_PLUS_ONE)).thenReturn(List.of());
        List<SourceBlock> blocks = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            blocks.add(text(i, "Đoạn số " + i + " " + "chữ ".repeat(30)));
        }
        when(index.lessonBlocks(3, 88)).thenReturn(blocks);

        LessonText read = reader.read(26, "Tín dụng", 3, 88, "Bài 1");

        assertThat(read.hits()).hasSize(LessonReader.MAX_CHUNKS_PLUS_ONE - 1);
        assertThat(read.truncated()).isTrue();
        assertThat(read.hits().get(0).content()).contains("Đoạn số 1");
    }
}
