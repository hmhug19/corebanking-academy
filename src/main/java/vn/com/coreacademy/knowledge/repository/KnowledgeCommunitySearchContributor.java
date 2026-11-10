package vn.com.coreacademy.knowledge.repository;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.search.SearchContributor;
import vn.com.coreacademy.knowledge.service.KnowledgeAccess;
import vn.com.coreacademy.security.SecurityContextUtils;

/** Chỉ tài liệu ready mà người gọi được đọc (theo phạm vi tài liệu) được đưa vào tìm kiếm. */
@Repository
@RequiredArgsConstructor
public class KnowledgeCommunitySearchContributor implements SearchContributor {
    private final KnowledgeAccess access;

    @Override
    public String sourceType() {
        return "knowledge";
    }

    @Override
    public Part contribute(Filter filter) {
        String userId = SecurityContextUtils.getUserId();
        DocumentVisibility visibility = access.visibility(userId);
        return new Part(sourceType(), """
            select 'knowledge'::text source_type, d.document_id source_id, null::bigint revision_id,
              d.file_name title, left(coalesce(texts.body, d.file_name), 400) snippet,
              cast(ts_rank_cd(to_tsvector('simple', d.file_name || ' ' || coalesce(texts.body, '')),
                plainto_tsquery('simple', :knowledgeQuery), 32) as double precision) score,
              extract(epoch from d.create_time) updated_epoch, '/knowledge/documents/' || d.document_id url,
              null::bigint answer_id
            from learning_hub.knowledge_documents d
            left join lateral (select string_agg(c.content, ' ' order by c.chunk_index) body
              from learning_hub.knowledge_document_chunks c where c.document_id = d.document_id) texts on true
            where d.status = 'ready' and (d.scope = 'global' or (d.scope = 'personal' and d.uploaded_by = :knowledgeUser)
              or (d.scope = 'unit' and d.scope_org_id in (:knowledgeOrgs))) and (:knowledgeQuery = '' or to_tsvector('simple', d.file_name || ' ' || coalesce(texts.body, ''))
              @@ plainto_tsquery('simple', :knowledgeQuery)
              or position(lower(:knowledgeQuery) in lower(d.file_name || ' ' || coalesce(texts.body, ''))) > 0)
            """, Map.of("knowledgeQuery", filter.q(), "knowledgeUser", userId, "knowledgeOrgs",
            visibility.orgIdsOrPlaceholder()));
    }
}
