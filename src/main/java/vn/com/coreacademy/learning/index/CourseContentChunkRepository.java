package vn.com.coreacademy.learning.index;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.search.VectorLiteral;

/**
 * Reads the text of published course revisions and keeps the chunk table in step with them. JPA has no mapping for
 * pgvector, so this uses JdbcTemplate. It never decides who may read a chunk; {@link CourseContentRetrieval} does.
 */
@Repository
@RequiredArgsConstructor
public class CourseContentChunkRepository {
    private static final String INSERT = """
        INSERT INTO learning_hub.course_content_chunks
            (course_id, revision_id, chapter_id, chapter_title, lesson_id, lesson_title, block_id, block_title,
             chunk_index, content, token_estimate, embedding)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
        """;

    // Chapter, lesson and block in reading order; process steps are folded into one text per block.
    private static final String BLOCKS = """
        SELECT ch.chapter_id, ch.title AS chapter_title, l.lesson_id, l.title AS lesson_title, b.block_id,
               b.title AS block_title, b.block_type, b.body, b.body_format, CAST(b.transcript AS text) AS transcript,
               (SELECT string_agg(s.title || coalesce(': ' || s.description, ''), E'\\n' ORDER BY s.sort_no)
                  FROM learning_hub.process_steps s WHERE s.block_id = b.block_id) AS steps
          FROM learning_hub.course_chapters ch
          JOIN learning_hub.course_lessons l ON l.chapter_id = ch.chapter_id
          JOIN learning_hub.lesson_blocks b ON b.lesson_id = l.lesson_id
         WHERE ch.revision_id = ?
         ORDER BY ch.sort_no, ch.chapter_id, l.sort_no, l.lesson_id, b.sort_no, b.block_id
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /** A published revision the index does not cover yet. */
    public record PendingRevision(long courseId, long revisionId) {
    }

    /** One block of a revision as stored, before it is turned into text. */
    public record SourceBlock(long chapterId, String chapterTitle, long lessonId, String lessonTitle, long blockId,
                              String blockTitle, String blockType, String body, String bodyFormat, String steps,
                              JsonNode transcript) {
    }

    /** One chunk to store; {@code embedding} is the bge-m3 vector of the lesson title and the content. */
    public record NewChunk(SourceBlock block, int index, String content, int tokenEstimate, float[] embedding) {
    }

    /** Published revisions with no completed index entry, oldest course first. */
    public List<PendingRevision> pending(int limit) {
        return jdbc.query("""
            SELECT c.course_id, c.published_revision_id
              FROM learning_hub.courses c
              JOIN learning_hub.course_revisions r ON r.revision_id = c.published_revision_id AND r.state = 'published'
             WHERE NOT EXISTS (SELECT 1 FROM learning_hub.course_content_index i
                                WHERE i.revision_id = c.published_revision_id)
             ORDER BY c.course_id
             LIMIT ?
            """, (rs, rowNum) -> new PendingRevision(rs.getLong(1), rs.getLong(2)), limit);
    }

    public List<SourceBlock> blocks(long revisionId) {
        return jdbc.query(BLOCKS, (rs, rowNum) -> new SourceBlock(rs.getLong("chapter_id"),
            rs.getString("chapter_title"), rs.getLong("lesson_id"), rs.getString("lesson_title"),
            rs.getLong("block_id"), rs.getString("block_title"), rs.getString("block_type"), rs.getString("body"),
            rs.getString("body_format"), rs.getString("steps"), parse(rs.getString("transcript"))), revisionId);
    }

    /** The blocks of one lesson of a revision in reading order, as {@link #blocks(long)} reads them. */
    public List<SourceBlock> lessonBlocks(long revisionId, long lessonId) {
        return jdbc.query(BLOCKS.replace("WHERE ch.revision_id = ?", "WHERE ch.revision_id = ? AND l.lesson_id = ?"),
            (rs, rowNum) -> new SourceBlock(rs.getLong("chapter_id"), rs.getString("chapter_title"),
                rs.getLong("lesson_id"), rs.getString("lesson_title"), rs.getLong("block_id"),
                rs.getString("block_title"), rs.getString("block_type"), rs.getString("body"),
                rs.getString("body_format"), rs.getString("steps"), parse(rs.getString("transcript"))),
            revisionId, lessonId);
    }

    /**
     * Replaces everything indexed for a revision. Run it inside one transaction: the old chunks, the new chunks and
     * the completion marker commit together, so an interrupted run leaves the revision as pending, never half indexed.
     */
    public void replaceRevision(long courseId, long revisionId, List<NewChunk> chunks) {
        jdbc.update("DELETE FROM learning_hub.course_content_chunks WHERE revision_id = ?", revisionId);
        jdbc.batchUpdate(INSERT, chunks, chunks.size(), (statement, chunk) -> {
            SourceBlock block = chunk.block();
            statement.setLong(1, courseId);
            statement.setLong(2, revisionId);
            statement.setLong(3, block.chapterId());
            statement.setString(4, block.chapterTitle());
            statement.setLong(5, block.lessonId());
            statement.setString(6, block.lessonTitle());
            statement.setLong(7, block.blockId());
            statement.setString(8, block.blockTitle());
            statement.setInt(9, chunk.index());
            statement.setString(10, chunk.content());
            statement.setInt(11, chunk.tokenEstimate());
            statement.setString(12, VectorLiteral.encode(chunk.embedding()));
        });
        jdbc.update("""
            INSERT INTO learning_hub.course_content_index (revision_id, course_id, chunk_count)
            VALUES (?, ?, ?)
            ON CONFLICT (revision_id) DO UPDATE SET chunk_count = EXCLUDED.chunk_count, indexed_time = now()
            """, revisionId, courseId, chunks.size());
    }

    /** Removes chunks and markers of revisions that are no longer the published one of their course. */
    public int dropSuperseded() {
        int chunks = jdbc.update("""
            DELETE FROM learning_hub.course_content_chunks ch
             WHERE NOT EXISTS (SELECT 1 FROM learning_hub.courses c
                                WHERE c.course_id = ch.course_id AND c.published_revision_id = ch.revision_id)
            """);
        jdbc.update("""
            DELETE FROM learning_hub.course_content_index i
             WHERE NOT EXISTS (SELECT 1 FROM learning_hub.courses c
                                WHERE c.course_id = i.course_id AND c.published_revision_id = i.revision_id)
            """);

        return chunks;
    }

    private JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (IOException exception) {
            return null;
        }
    }
}
