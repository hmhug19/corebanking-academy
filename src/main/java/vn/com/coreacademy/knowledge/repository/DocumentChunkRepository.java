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

    private final JdbcTemplate jdbc;

    /** One chunk to store; {@code embedding} is the bge-m3 vector of {@code content}. */
    public record NewChunk(int index, String content, Integer page, int lineStart, int lineEnd, int tokenEstimate,
                           float[] embedding) {
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
