package vn.com.coreacademy.learning.index;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.search.VectorLiteral;
import vn.com.coreacademy.knowledge.chunking.ChunkText;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

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
             chunk_index, content, token_estimate, embedding, page_number, section_path, content_hash,
             chunker_version, embed_model)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, ?, ?, ?, ?)
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

    // x is the alias of the table that holds course_id and revision_id.
    private static final String SUPERSEDED = """
        NOT EXISTS (SELECT 1 FROM learning_hub.courses c
                     WHERE c.course_id = x.course_id AND c.published_revision_id = x.revision_id)
        AND NOT EXISTS (SELECT 1 FROM learning_hub.course_enrollments e
                         WHERE e.revision_id = x.revision_id AND e.status <> 'withdrawn')
        AND NOT EXISTS (SELECT 1 FROM learning_hub.courses c
                         WHERE c.course_id = x.course_id AND c.published_revision_id IS NOT NULL
                           AND NOT EXISTS (SELECT 1 FROM learning_hub.course_content_index i
                                            WHERE i.revision_id = c.published_revision_id AND i.status = 'done'))
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KnowledgeProperties properties;

    /** A published revision the index does not cover yet. */
    public record PendingRevision(long courseId, long revisionId) {
    }

    /** One block of a revision as stored, before it is turned into text. */
    public record SourceBlock(long chapterId, String chapterTitle, long lessonId, String lessonTitle, long blockId,
                              String blockTitle, String blockType, String body, String bodyFormat, String steps,
                              JsonNode transcript) {
    }

    /** One chunk to store; {@code embedding} is the bge-m3 vector of the lesson title and the content. */
    public record NewChunk(SourceBlock block, int index, String content, int tokenEstimate, float[] embedding,
                           Integer page, String sectionPath) {
        public NewChunk(SourceBlock block, int index, String content, int tokenEstimate, float[] embedding,
                        Integer page) {
            this(block, index, content, tokenEstimate, embedding, page, "");
        }

        public NewChunk(SourceBlock block, int index, String content, int tokenEstimate, float[] embedding) {
            this(block, index, content, tokenEstimate, embedding, null, "");
        }
    }

    /** A file attached to a pdf or attachment block, as stored in the object storage. */
    public record BlockAsset(long assetId, String storageKey, String originalName, String mimeType, long sizeBytes) {
    }

    /** A revision whose last indexing attempts failed, for the admin list. */
    public record FailedRevision(long courseId, long revisionId, String status, int attempts, String errorType,
                                 Instant lastAttempt) {
    }

    /**
     * Revisions to index, oldest course first: the published revision of a course, and any published revision a
     * learner still has an active enrolment on (it may have been superseded before it was ever indexed), that have no
     * index entry yet or whose retry time has come. A revision marked failed waits for an admin.
     */
    public List<PendingRevision> pending(int limit) {
        return jdbc.query("""
            SELECT r.course_id, r.revision_id
              FROM learning_hub.course_revisions r
              JOIN learning_hub.courses c ON c.course_id = r.course_id
             WHERE r.state = 'published'
               AND (r.revision_id = c.published_revision_id OR EXISTS (
                        SELECT 1 FROM learning_hub.course_enrollments e
                         WHERE e.revision_id = r.revision_id AND e.status <> 'withdrawn'))
               AND NOT EXISTS (SELECT 1 FROM learning_hub.course_content_index i
                                WHERE i.revision_id = r.revision_id
                                  AND (i.status IN ('done', 'failed')
                                       OR (i.status = 'retry' AND i.next_attempt_time > now())))
             ORDER BY r.course_id, r.revision_id
             LIMIT ?
            """, (rs, rowNum) -> new PendingRevision(rs.getLong(1), rs.getLong(2)), limit);
    }

    /** Files attached to the pdf and attachment blocks of a revision, by block, in the order they were attached. */
    public Map<Long, List<BlockAsset>> blockAssets(long revisionId) {
        Map<Long, List<BlockAsset>> byBlock = new LinkedHashMap<>();
        jdbc.query("""
            SELECT ba.block_id, a.asset_id, a.storage_key, a.original_name, a.mime_type, a.size_bytes
              FROM learning_hub.course_chapters ch
              JOIN learning_hub.course_lessons l ON l.chapter_id = ch.chapter_id
              JOIN learning_hub.lesson_blocks b ON b.lesson_id = l.lesson_id
              JOIN learning_hub.block_assets ba ON ba.block_id = b.block_id
              JOIN learning_hub.assets a ON a.asset_id = ba.asset_id
             WHERE ch.revision_id = ? AND b.block_type IN ('pdf', 'attachment')
               AND ba.usage IN ('primary', 'attachment') AND a.storage_type = 's3' AND a.storage_key IS NOT NULL
               AND a.status = 'ready' AND a.is_deleted = false
             ORDER BY ba.block_id, ba.sort_no, ba.block_asset_id
            """, rs -> {
                byBlock.computeIfAbsent(rs.getLong("block_id"), id -> new ArrayList<>())
                    .add(new BlockAsset(rs.getLong("asset_id"), rs.getString("storage_key"),
                        rs.getString("original_name"), rs.getString("mime_type"), rs.getLong("size_bytes")));
            }, revisionId);

        return byBlock;
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
            statement.setString(12, VectorLiteral.encode(chunk.embedding(), properties.ollama().embedDimensions()));
            statement.setObject(13, chunk.page());
            statement.setString(14, chunk.sectionPath());
            statement.setString(15, ChunkText.sha256(chunk.content()));
            statement.setInt(16, properties.chunk().version());
            statement.setString(17, properties.ollama().embedModel());
        });
        jdbc.update("""
            INSERT INTO learning_hub.course_content_index (revision_id, course_id, chunk_count)
            VALUES (?, ?, ?)
            ON CONFLICT (revision_id) DO UPDATE SET chunk_count = EXCLUDED.chunk_count, indexed_time = now(),
                status = 'done', attempts = 0, next_attempt_time = NULL, error_type = NULL
            """, revisionId, courseId, chunks.size());
    }

    /**
     * Records a failed attempt. The revision is retried after {@code retryDelay} (doubling each time) until
     * {@code maxAttempts} attempts have failed, then it is marked failed and left for an admin. {@code errorType} is
     * a class name, never course text.
     */
    public void recordFailure(long courseId, long revisionId, String errorType, int maxAttempts,
                              Duration retryDelay) {
        double seconds = retryDelay.toSeconds();
        jdbc.update("""
            INSERT INTO learning_hub.course_content_index AS i
                (revision_id, course_id, chunk_count, status, attempts, next_attempt_time, error_type)
            VALUES (?, ?, 0, CASE WHEN 1 >= ? THEN 'failed' ELSE 'retry' END, 1,
                    CASE WHEN 1 >= ? THEN NULL ELSE now() + make_interval(secs => ?) END, ?)
            ON CONFLICT (revision_id) DO UPDATE SET
                attempts = i.attempts + 1, indexed_time = now(), error_type = EXCLUDED.error_type,
                status = CASE WHEN i.attempts + 1 >= ? THEN 'failed' ELSE 'retry' END,
                next_attempt_time = CASE WHEN i.attempts + 1 >= ? THEN NULL
                    ELSE now() + make_interval(secs => ? * power(2, i.attempts)) END
             WHERE i.status <> 'done'
            """, revisionId, courseId, maxAttempts, maxAttempts, seconds, errorType, maxAttempts, maxAttempts,
            seconds);
    }

    /** Revisions the worker is retrying or has given up on, newest first. */
    public List<FailedRevision> failed() {
        return jdbc.query("""
            SELECT course_id, revision_id, status, attempts, error_type, indexed_time
              FROM learning_hub.course_content_index WHERE status <> 'done'
             ORDER BY indexed_time DESC, revision_id DESC
            """, (rs, rowNum) -> new FailedRevision(rs.getLong("course_id"), rs.getLong("revision_id"),
            rs.getString("status"), rs.getInt("attempts"), rs.getString("error_type"),
            rs.getTimestamp("indexed_time").toInstant()));
    }

    /** Indexed revisions with at least one chunk cut by another chunker version or embedded by another model. */
    public List<PendingRevision> staleRevisions() {
        return jdbc.query("""
            SELECT i.course_id, i.revision_id FROM learning_hub.course_content_index i
             WHERE i.status = 'done' AND EXISTS (
                   SELECT 1 FROM learning_hub.course_content_chunks c
                    WHERE c.revision_id = i.revision_id AND (c.chunker_version <> ? OR c.embed_model <> ?))
             ORDER BY i.course_id, i.revision_id
            """, (rs, rowNum) -> new PendingRevision(rs.getLong(1), rs.getLong(2)),
            properties.chunk().version(), properties.ollama().embedModel());
    }

    /** The course title of a revision, the head of every embedded text of its chunks. */
    public String courseTitle(long revisionId) {
        return jdbc.queryForObject("SELECT title FROM learning_hub.course_revisions WHERE revision_id = ?",
            String.class, revisionId);
    }

    /** Puts a failed revision back in the queue; false when it is not failing. */
    public boolean retry(long revisionId) {
        return jdbc.update("DELETE FROM learning_hub.course_content_index WHERE revision_id = ? AND status <> 'done'",
            revisionId) > 0;
    }

    /**
     * Removes chunks and markers of revisions that are neither the published one of their course nor the revision of
     * an active enrolment. Nothing is removed from a course whose published revision is not indexed yet, so the
     * previous revision keeps answering until the new one is in.
     */
    public int dropSuperseded() {
        int chunks = jdbc.update("DELETE FROM learning_hub.course_content_chunks x WHERE " + SUPERSEDED);
        jdbc.update("DELETE FROM learning_hub.course_content_index x WHERE " + SUPERSEDED);

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
