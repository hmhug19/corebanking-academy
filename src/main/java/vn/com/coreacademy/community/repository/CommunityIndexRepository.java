package vn.com.coreacademy.community.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.search.VectorLiteral;
import vn.com.coreacademy.community.entity.CommunityContent;

@Repository
@RequiredArgsConstructor
public class CommunityIndexRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;

    public List<Long> contentIds(Long org, Long after, Long guild) {
        return repository.page("""
            select c.contentId from CommunityContent c where c.ownerOrgId=:org and c.contentId>:after
              and (:allGuilds=true or c.primaryGuildId=:guild or exists (
                select cg.contentGuildId from ContentGuild cg where cg.contentId=c.contentId and cg.guildId=:guild))
            order by c.contentId
            """, Long.class, Map.of("org", org, "after", after, "allGuilds", guild == null,
                "guild", guild == null ? -1L : guild), 0, 20);
    }

    public List<Long> affected(Long contentId) {
        return repository.list("""
            select c.contentId from CommunityContent c where c.contentId=:content or exists (
              select a.contentId from Answer a where a.contentId=c.contentId and a.questionContentId=:content)
            order by c.contentId
            """, Long.class, Map.of("content", contentId));
    }

    public void stale(Long contentId, String actor) {
        entityManager.createNativeQuery("""
            update learning_hub.community_search_chunks set index_status='failed',updated_by=:actor,
              update_time=now(),row_version=row_version+1 where content_id=:content and index_status='ready'
            """).setParameter("content", contentId).setParameter("actor", actor).executeUpdate();
    }

    public void upsert(Long content, Long revision, Long transcript, int number, String body, String model,
                        float[] vector, BigDecimal start, BigDecimal end, String actor) {
        var existing = entityManager.createNativeQuery("""
            select chunk_id from learning_hub.community_search_chunks where revision_id=:revision
              and coalesce(transcript_id,-1)=:transcript and chunk_no=:number and embedding_model=:model
            order by chunk_id
            """).setParameter("revision", revision).setParameter("transcript", transcript == null ? -1L : transcript)
            .setParameter("number", number).setParameter("model", model).getResultList();
        if (existing.isEmpty()) {
            entityManager.createNativeQuery("""
                insert into learning_hub.community_search_chunks
                  (content_id,revision_id,transcript_id,chunk_no,body_text,embedding,embedding_model,index_status,
                   start_seconds,end_seconds,created_by,updated_by,create_time,update_time,row_version)
                values (:content,:revision,:transcript,:number,:body,cast(:vector as vector),:model,'ready',
                  :start,:end,:actor,:actor,now(),now(),0)
                """).setParameter("content", content).setParameter("revision", revision)
                .setParameter("transcript", transcript).setParameter("number", number).setParameter("body", body)
                .setParameter("vector", VectorLiteral.encode(vector)).setParameter("model", model)
                .setParameter("start", start).setParameter("end", end).setParameter("actor", actor).executeUpdate();
        } else {
            entityManager.createNativeQuery("""
                update learning_hub.community_search_chunks set body_text=:body,embedding=cast(:vector as vector),
                  index_status='ready',start_seconds=:start,end_seconds=:end,updated_by=:actor,
                  update_time=now(),row_version=row_version+1 where chunk_id=:id
                """).setParameter("id", existing.get(0)).setParameter("body", body)
                .setParameter("vector", VectorLiteral.encode(vector)).setParameter("start", start)
                .setParameter("end", end).setParameter("actor", actor).executeUpdate();
        }
    }
}
