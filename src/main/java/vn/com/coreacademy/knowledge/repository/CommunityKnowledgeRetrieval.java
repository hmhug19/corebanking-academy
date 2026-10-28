package vn.com.coreacademy.knowledge.repository;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.search.KnowledgeSource;
import vn.com.coreacademy.common.search.VectorLiteral;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.security.SecurityContextUtils;

@Repository
@RequiredArgsConstructor
public class CommunityKnowledgeRetrieval implements KnowledgeSource {
    private final JdbcTemplate jdbc;
    private final LearningIdentityService identity;

    @Override
    public String sourceType() {
        return "knowledge";
    }

    @Override
    public List<Excerpt> retrieve(float[] vector, String question, Long guildId, int limit, String model) {
        identity.actor(SecurityContextUtils.getUserId());
        return jdbc.query("""
            select c.chunk_id,c.document_id,d.file_name,left(c.content,2000),c.page_number,c.line_start,c.line_end,
              1-(c.embedding <=> cast(? as vector)) similarity
            from learning_hub.knowledge_document_chunks c join learning_hub.knowledge_documents d
              on d.document_id=c.document_id where d.status='ready'
            order by c.embedding <=> cast(? as vector),c.chunk_id limit ?
            """, (row, index) -> new Excerpt(new Pointer(sourceType(), row.getLong(2), null, row.getLong(1)),
                row.getString(3), row.getString(4), "/knowledge/documents/" + row.getLong(2),
                Map.of("page", row.getInt(5), "lineStart", row.getInt(6), "lineEnd", row.getInt(7)), row.getDouble(8)),
            VectorLiteral.encode(vector), VectorLiteral.encode(vector), limit);
    }

    @Override
    public Excerpt resolve(Pointer pointer) {
        identity.actor(SecurityContextUtils.getUserId());
        var result = jdbc.query("""
            select c.chunk_id,c.document_id,d.file_name,left(c.content,2000),c.page_number,c.line_start,c.line_end
            from learning_hub.knowledge_document_chunks c join learning_hub.knowledge_documents d
              on d.document_id=c.document_id where d.status='ready' and c.chunk_id=?
                and c.document_id=coalesce(cast(? as bigint),c.document_id)
            """, (row, index) -> new Excerpt(new Pointer(sourceType(), row.getLong(2), null, row.getLong(1)), row.getString(3), row.getString(4),
                "/knowledge/documents/" + row.getLong(2), Map.of("page", row.getInt(5), "lineStart", row.getInt(6),
                    "lineEnd", row.getInt(7)), 1), pointer.chunkId(), pointer.sourceId());
        return result.isEmpty() ? null : result.get(0);
    }
}
