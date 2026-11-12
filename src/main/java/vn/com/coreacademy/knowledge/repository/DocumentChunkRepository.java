package vn.com.coreacademy.knowledge.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.knowledge.chunking.ChunkText;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

/** Chunks of ready documents; JPA has no mapping for pgvector, so this uses JdbcTemplate. */
@Repository
@RequiredArgsConstructor
public class DocumentChunkRepository {
    private static final String INSERT = """
        INSERT INTO learning_hub.knowledge_document_chunks
            (document_id, chunk_index, content, page_number, line_start, line_end, token_estimate, embedding,
             section_path, content_hash, chunker_version, embed_model)
        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector), ?, ?, ?, ?)
        """;

    // Cosine distance with the HNSW index; 1 - distance is the similarity. Only ready documents have chunks.
    private static final String SEARCH = """
        SELECT c.chunk_id, c.document_id, d.file_name, c.page_number, c.line_start, c.line_end, c.content,
               c.section_path, 1 - (c.embedding <=> CAST(:vector AS vector)) AS similarity
          FROM learning_hub.knowledge_document_chunks c
          JOIN learning_hub.knowledge_documents d ON d.document_id = c.document_id
         WHERE d.scope = 'global'
            OR (d.scope = 'personal' AND d.uploaded_by = :userId)
            OR (d.scope = 'unit' AND d.scope_org_id IN (:orgIds))
         ORDER BY c.embedding <=> CAST(:vector AS vector)
         LIMIT :limit
        """;

    // The chunks next to the given ones in the same document, under the same visibility rule as SEARCH.
    private static final String NEIGHBOURS = """
        SELECT c.chunk_id AS parent_id, n.chunk_id, n.document_id, d.file_name, n.page_number, n.line_start,
               n.line_end, n.content, n.section_path
          FROM learning_hub.knowledge_document_chunks c
          JOIN learning_hub.knowledge_document_chunks n
            ON n.document_id = c.document_id AND n.chunk_index IN (c.chunk_index - 1, c.chunk_index + 1)
          JOIN learning_hub.knowledge_documents d ON d.document_id = c.document_id
         WHERE c.chunk_id IN (:ids)
           AND (d.scope = 'global'
                OR (d.scope = 'personal' AND d.uploaded_by = :userId)
                OR (d.scope = 'unit' AND d.scope_org_id IN (:orgIds)))
         ORDER BY c.chunk_id, n.chunk_index
        """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final KnowledgeProperties properties;

    /** One chunk to store; {@code embedding} is the bge-m3 vector of {@code content}. */
    public record NewChunk(int index, String content, Integer page, int lineStart, int lineEnd, int tokenEstimate,
                           float[] embedding, String sectionPath) {
        /** A chunk outside any section. */
        public NewChunk(int index, String content, Integer page, int lineStart, int lineEnd, int tokenEstimate,
                        float[] embedding) {
            this(index, content, page, lineStart, lineEnd, tokenEstimate, embedding, "");
        }
    }

    /** A chunk found by {@link #search}, with the metadata a citation needs. */
    public record ChunkHit(long chunkId, long documentId, String fileName, Integer page, int lineStart, int lineEnd,
                           String content, double similarity, LessonSource lesson, String sectionPath) {
        public ChunkHit {
            sectionPath = sectionPath == null ? "" : sectionPath;
        }

        /** A chunk of a lesson (or a document) with no section path. */
        public ChunkHit(long chunkId, long documentId, String fileName, Integer page, int lineStart, int lineEnd,
                        String content, double similarity, LessonSource lesson) {
            this(chunkId, documentId, fileName, page, lineStart, lineEnd, content, similarity, lesson, "");
        }

        /** A chunk of a document with a section path. */
        public ChunkHit(long chunkId, long documentId, String fileName, Integer page, int lineStart, int lineEnd,
                        String content, double similarity, String sectionPath) {
            this(chunkId, documentId, fileName, page, lineStart, lineEnd, content, similarity, null, sectionPath);
        }

        /** A chunk of a document; it has no lesson. */
        public ChunkHit(long chunkId, long documentId, String fileName, Integer page, int lineStart, int lineEnd,
                        String content, double similarity) {
            this(chunkId, documentId, fileName, page, lineStart, lineEnd, content, similarity, (LessonSource) null);
        }

        /**
         * Chunk ids of documents and of lessons come from two tables, so two different chunks can share an id; this
         * key tells them apart wherever hits of both kinds are collected.
         */
        public String key() {
            return (lesson == null ? "d" : "l") + chunkId;
        }
    }

    /** Where a chunk of published course content comes from; the link opens the lesson. */
    public record LessonSource(long courseId, String courseTitle, long revisionId, long chapterId,
                               String chapterTitle, long lessonId, String lessonTitle, long blockId,
                               String blockTitle, String url) {
    }

    /**
     * The {@code limit} chunks closest to {@code query} among the documents {@code visibility} may read, best first.
     * The scope filter is part of the same statement as the ordering and the limit, so documents the user cannot read
     * never take a place in the top results.
     */
    public List<ChunkHit> search(float[] query, int limit, DocumentVisibility visibility) {
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("vector", toVectorLiteral(query))
            .addValue("limit", limit).addValue("userId", visibility.userId())
            .addValue("orgIds", visibility.orgIdsOrPlaceholder());

        return named.query(SEARCH, parameters, (rs, rowNum) -> new ChunkHit(rs.getLong("chunk_id"),
            rs.getLong("document_id"), rs.getString("file_name"), rs.getObject("page_number", Integer.class),
            rs.getInt("line_start"), rs.getInt("line_end"), rs.getString("content"), rs.getDouble("similarity"),
            rs.getString("section_path")));
    }

    /**
     * For each chunk in {@code similarityOf} (chunks the caller already found, with their similarity), the chunks at
     * {@code chunk_index} plus and minus one of the same document, previous before next. The visibility rule is part
     * of the statement, so a neighbour is returned only when its document is one the caller may search. A neighbour
     * carries the similarity of the chunk it sits next to.
     */
    public Map<Long, List<ChunkHit>> neighbours(Map<Long, Double> similarityOf, DocumentVisibility visibility) {
        Map<Long, List<ChunkHit>> byParent = new LinkedHashMap<>();
        if (similarityOf.isEmpty()) {
            return byParent;
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("ids", similarityOf.keySet())
            .addValue("userId", visibility.userId()).addValue("orgIds", visibility.orgIdsOrPlaceholder());
        named.query(NEIGHBOURS, parameters, rs -> {
            long parent = rs.getLong("parent_id");
            byParent.computeIfAbsent(parent, id -> new ArrayList<>()).add(new ChunkHit(rs.getLong("chunk_id"),
                rs.getLong("document_id"), rs.getString("file_name"), rs.getObject("page_number", Integer.class),
                rs.getInt("line_start"), rs.getInt("line_end"), rs.getString("content"), similarityOf.get(parent),
                rs.getString("section_path")));
        });

        return byParent;
    }

    /** Ready documents with at least one chunk cut by another chunker version or embedded by another model. */
    public List<Long> staleDocuments() {
        return jdbc.queryForList("""
            SELECT d.document_id FROM learning_hub.knowledge_documents d
             WHERE d.status = 'ready' AND EXISTS (
                   SELECT 1 FROM learning_hub.knowledge_document_chunks c
                    WHERE c.document_id = d.document_id AND (c.chunker_version <> ? OR c.embed_model <> ?))
             ORDER BY d.document_id
            """, Long.class, properties.chunk().version(), properties.ollama().embedModel());
    }

    /**
     * Swaps all chunks of a ready document for new ones and returns true; false when the document is gone or no longer
     * ready (deleted or replaced meanwhile), in which case nothing is written. Run it inside one transaction with the
     * embedding already done: a search sees the old chunks until the commit and the new ones after it, never none.
     * The document row is locked in share mode, so a delete waits for the swap instead of leaving orphan chunks.
     */
    public boolean replaceIfReady(long documentId, List<NewChunk> chunks) {
        boolean ready = !jdbc.queryForList("""
            SELECT 1 FROM learning_hub.knowledge_documents WHERE document_id = ? AND status = 'ready' FOR SHARE
            """, Integer.class, documentId).isEmpty();
        if (!ready) {
            return false;
        }
        deleteByDocument(documentId);
        insertAll(documentId, chunks);
        jdbc.update("UPDATE learning_hub.knowledge_documents SET chunk_count = ? WHERE document_id = ?",
            chunks.size(), documentId);

        return true;
    }

    /** Removes every chunk of a document; the caller deletes the document row in the same transaction. */
    public int deleteByDocument(long documentId) {
        return jdbc.update("DELETE FROM learning_hub.knowledge_document_chunks WHERE document_id = ?", documentId);
    }

    /** Must run inside the transaction that marks the document ready, so a search never sees half a document. */
    public void insertAll(long documentId, List<NewChunk> chunks) {
        jdbc.batchUpdate(INSERT, chunks, chunks.size(), (statement, chunk) -> {
            statement.setLong(1, documentId);
            statement.setInt(2, chunk.index());
            statement.setString(3, chunk.content());
            statement.setObject(4, chunk.page());
            statement.setInt(5, chunk.lineStart());
            statement.setInt(6, chunk.lineEnd());
            statement.setInt(7, chunk.tokenEstimate());
            statement.setString(8, toVectorLiteral(chunk.embedding()));
            statement.setString(9, chunk.sectionPath());
            statement.setString(10, ChunkText.sha256(chunk.content()));
            statement.setInt(11, properties.chunk().version());
            statement.setString(12, properties.ollama().embedModel());
        });
    }

    /** pgvector text format: {@code [0.1,0.2,...]}. */
    static String toVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
