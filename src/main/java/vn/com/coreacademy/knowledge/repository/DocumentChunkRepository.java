package vn.com.coreacademy.knowledge.repository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Chunks of ready documents; JPA has no mapping for pgvector, so this uses JdbcTemplate. */
@Repository
@RequiredArgsConstructor
public class DocumentChunkRepository {
    private static final String INSERT = """
        INSERT INTO learning_hub.knowledge_document_chunks
            (document_id, chunk_index, content, page_number, line_start, line_end, token_estimate, embedding)
        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
        """;

    // Cosine distance with the HNSW index; 1 - distance is the similarity. Only ready documents have chunks.
    private static final String SEARCH = """
        SELECT c.chunk_id, c.document_id, d.file_name, c.page_number, c.line_start, c.line_end, c.content,
               1 - (c.embedding <=> CAST(:vector AS vector)) AS similarity
          FROM learning_hub.knowledge_document_chunks c
          JOIN learning_hub.knowledge_documents d ON d.document_id = c.document_id
         WHERE d.scope = 'global'
            OR (d.scope = 'personal' AND d.uploaded_by = :userId)
            OR (d.scope = 'unit' AND d.scope_org_id IN (:orgIds))
         ORDER BY c.embedding <=> CAST(:vector AS vector)
         LIMIT :limit
        """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    /** One chunk to store; {@code embedding} is the bge-m3 vector of {@code content}. */
    public record NewChunk(int index, String content, Integer page, int lineStart, int lineEnd, int tokenEstimate,
                           float[] embedding) {
    }

    /** A chunk found by {@link #search}, with the metadata a citation needs. */
    public record ChunkHit(long chunkId, long documentId, String fileName, Integer page, int lineStart, int lineEnd,
                           String content, double similarity, LessonSource lesson) {
        /** A chunk of a document; it has no lesson. */
        public ChunkHit(long chunkId, long documentId, String fileName, Integer page, int lineStart, int lineEnd,
                        String content, double similarity) {
            this(chunkId, documentId, fileName, page, lineStart, lineEnd, content, similarity, null);
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
            rs.getInt("line_start"), rs.getInt("line_end"), rs.getString("content"), rs.getDouble("similarity")));
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
