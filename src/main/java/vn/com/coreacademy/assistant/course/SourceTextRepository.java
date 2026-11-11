package vn.com.coreacademy.assistant.course;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The text of a job's source document, as the ingestion worker cut it, in reading order. */
@Repository
@RequiredArgsConstructor
public class SourceTextRepository {
    /** A piece of the source with the place it came from. */
    public record Piece(int index, String content, Integer page, int lineStart, int lineEnd) {
        /** The piece as the model reads it: where it is, then the text. */
        public String labelled() {
            String place = (page == null ? "" : "trang " + page + ", ") + "dòng " + lineStart + "–" + lineEnd;
            return "[" + place + "]\n" + content;
        }
    }

    public record Status(String status, String error) {
    }

    private final JdbcTemplate jdbc;

    public Optional<Status> status(long documentId) {
        return jdbc.query("SELECT status, error_message FROM learning_hub.knowledge_documents WHERE document_id = ?",
            (rs, row) -> new Status(rs.getString("status"), rs.getString("error_message")), documentId).stream()
            .findFirst();
    }

    public List<Piece> pieces(long documentId) {
        return jdbc.query("""
            SELECT chunk_index, content, page_number, line_start, line_end
              FROM learning_hub.knowledge_document_chunks WHERE document_id = ? ORDER BY chunk_index
            """, (rs, row) -> new Piece(rs.getInt("chunk_index"), rs.getString("content"),
            (Integer) rs.getObject("page_number"), rs.getInt("line_start"), rs.getInt("line_end")), documentId);
    }
}
