package vn.com.coreacademy.learning.index;

import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.chunking.Chunk;
import vn.com.coreacademy.knowledge.chunking.Chunker;
import vn.com.coreacademy.knowledge.extraction.TextLine;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.LessonSource;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.SourceBlock;

/**
 * Reads one lesson for the assistant: the indexed chunks when there are any, else the lesson's blocks straight from the
 * database with the same text rules as the indexer, so a lesson that is not indexed yet still answers. The caller has
 * already checked that the user may read the lesson; this never decides access. A direct read cites the lesson itself,
 * not a block, so its chunks carry negative ids that cannot meet an indexed chunk.
 */
@Component
@RequiredArgsConstructor
public class LessonReader {
    /** A summary reads this many chunks of the lesson; the answer says so when the lesson has more. */
    public static final int MAX_CHUNKS = CourseQuestionSearch.LESSON_CHUNKS;
    public static final int MAX_CHUNKS_PLUS_ONE = MAX_CHUNKS + 1;

    private final CourseContentRetrieval retrieval;
    private final CourseContentChunkRepository index;
    private final Chunker chunker;

    /**
     * What was read: {@code hits} in reading order, {@code truncated} when the lesson has more than was read, and
     * {@code noText} when the lesson has no text at all (a video without transcript, an image).
     */
    public record LessonText(List<ChunkHit> hits, boolean truncated, boolean noText) {
    }

    public LessonText read(long courseId, String courseTitle, long revisionId, long lessonId, String lessonTitle) {
        List<ChunkHit> indexed = retrieval.lessonChunks(lessonId, MAX_CHUNKS_PLUS_ONE).stream()
            .map(CourseQuestionSearch::asChunkHit).toList();
        if (!indexed.isEmpty()) {
            return new LessonText(indexed.stream().limit(MAX_CHUNKS).toList(), indexed.size() > MAX_CHUNKS, false);
        }

        return fromBlocks(courseId, courseTitle, revisionId, lessonId, lessonTitle,
            index.lessonBlocks(revisionId, lessonId));
    }

    LessonText fromBlocks(long courseId, String courseTitle, long revisionId, long lessonId, String lessonTitle,
                          List<SourceBlock> blocks) {
        List<ChunkHit> hits = new ArrayList<>();
        for (SourceBlock block : blocks) {
            for (Chunk chunk : chunker.chunk(lines(CourseContentIndexer.text(block)))) {
                LessonSource source = new LessonSource(courseId, courseTitle, revisionId, block.chapterId(),
                    block.chapterTitle(), lessonId, lessonTitle, 0L, null, "/lesson/" + courseId + "/" + lessonId);
                hits.add(new ChunkHit(-(hits.size() + 1L), 0L, courseTitle + " › " + lessonTitle, null, 0, 0,
                    chunk.content(), 0.0, source));
            }
        }
        if (hits.isEmpty()) {
            return new LessonText(List.of(), false, true);
        }

        return new LessonText(hits.stream().limit(MAX_CHUNKS).toList(), hits.size() > MAX_CHUNKS, false);
    }

    private static List<TextLine> lines(String text) {
        String[] raw = text.split("\\R");
        List<TextLine> lines = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            lines.add(new TextLine(null, i + 1, raw[i].strip()));
        }

        return lines;
    }
}
