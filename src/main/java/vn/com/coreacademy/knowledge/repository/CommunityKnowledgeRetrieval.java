package vn.com.coreacademy.knowledge.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.search.KnowledgeSource;
import vn.com.coreacademy.common.search.VectorLiteral;
import vn.com.coreacademy.knowledge.service.KnowledgeAccess;
import vn.com.coreacademy.security.SecurityContextUtils;

@Repository
@RequiredArgsConstructor
public class CommunityKnowledgeRetrieval implements KnowledgeSource {
    private final JdbcTemplate jdbc;
    private final KnowledgeAccess access;

    @Override
    public String sourceType() {
        return "knowledge";
    }

    @Override
    public List<Excerpt> retrieve(float[] vector, String question, Long guildId, int limit, String model) {
        String userId = SecurityContextUtils.getUserId();
        List<Long> orgs = List.copyOf(access.visibility(userId).orgIdsOrPlaceholder());
        return jdbc.query("""
            select c.chunk_id,c.document_id,d.file_name,left(c.content,2000),c.page_number,c.line_start,c.line_end,
              1-(c.embedding <=> cast(? as vector)) similarity
            from learning_hub.knowledge_document_chunks c join learning_hub.knowledge_documents d
              on d.document_id=c.document_id where d.status='ready'
              and (d.scope='global' or (d.scope='personal' and d.uploaded_by=?)
                or (d.scope='unit' and d.scope_org_id in (%s)))
            order by c.embedding <=> cast(? as vector),c.chunk_id limit ?
            """.formatted(placeholders(orgs.size())), (row, index) -> new Excerpt(new Pointer(sourceType(), row.getLong(2), null, row.getLong(1)),
                row.getString(3), row.getString(4), "/knowledge/documents/" + row.getLong(2),
                Map.of("page", row.getInt(5), "lineStart", row.getInt(6), "lineEnd", row.getInt(7)), row.getDouble(8)),
            arguments(VectorLiteral.encode(vector), userId, orgs, VectorLiteral.encode(vector), limit));
    }

    @Override
    public Excerpt resolve(Pointer pointer) {
        String userId = SecurityContextUtils.getUserId();
        List<Long> orgs = List.copyOf(access.visibility(userId).orgIdsOrPlaceholder());
        var result = jdbc.query("""
            select c.chunk_id,c.document_id,d.file_name,left(c.content,2000),c.page_number,c.line_start,c.line_end
            from learning_hub.knowledge_document_chunks c join learning_hub.knowledge_documents d
              on d.document_id=c.document_id where d.status='ready' and c.chunk_id=?
                and c.document_id=coalesce(cast(? as bigint),c.document_id)
                and (d.scope='global' or (d.scope='personal' and d.uploaded_by=?)
                  or (d.scope='unit' and d.scope_org_id in (%s)))
            """.formatted(placeholders(orgs.size())), (row, index) -> new Excerpt(new Pointer(sourceType(), row.getLong(2), null, row.getLong(1)), row.getString(3), row.getString(4),
                "/knowledge/documents/" + row.getLong(2), Map.of("page", row.getInt(5), "lineStart", row.getInt(6),
                    "lineEnd", row.getInt(7)), 1), arguments(pointer.chunkId(), pointer.sourceId(), userId, orgs));
        return result.isEmpty() ? null : result.get(0);
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    /** Flattens the lists in {@code values} so each element binds to its own placeholder, in order. */
    private static Object[] arguments(Object... values) {
        List<Object> flat = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof List<?> list) {
                flat.addAll(list);
            } else {
                flat.add(value);
            }
        }

        return flat.toArray();
    }
}
