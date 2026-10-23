package vn.com.coreacademy.knowledge.repository;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
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
               1 - (c.embedding <=> CAST(? AS vector)) AS similarity
          FROM learning_hub.knowledge_document_chunks c
          JOIN learning_hub.knowledge_documents d ON d.document_id = c.document_id
         ORDER BY c.embedding <=> CAST(? AS vector)
         LIMIT ?
        """;

    private final JdbcTemplate jdbc;

    /** One chunk to store; {@code embedding} is the bge-m3 vector of {@code content}. */
    public record NewChunk(int index, String content, Integer page, int lineStart, int lineEnd, int tokenEstimate,
                           float[] embedding) {
    }

    /** A chunk found by {@link #search}, with the metadata a citation needs. */
    public record ChunkHit(long chunkId, long documentId, String fileName, Integer page, int lineStart, int lineEnd,
                           String content, double similarity) {
    }

    /** The {@code limit} chunks closest to {@code query} across the whole shared knowledge base, best first. */
    public List<ChunkHit> search(float[] query, int limit) {
        String vector = toVectorLiteral(query);
        return jdbc.query(SEARCH, (rs, rowNum) -> new ChunkHit(rs.getLong("chunk_id"), rs.getLong("document_id"),
            rs.getString("file_name"), rs.getObject("page_number", Integer.class), rs.getInt("line_start"),
            rs.getInt("line_end"), rs.getString("content"), rs.getDouble("similarity")), vector, vector, limit);
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
