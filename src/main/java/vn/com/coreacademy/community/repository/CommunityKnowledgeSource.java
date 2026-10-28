package vn.com.coreacademy.community.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.search.KnowledgeSource;
import vn.com.coreacademy.common.search.VectorLiteral;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunitySearchChunk;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.MediaTranscript;
import vn.com.coreacademy.community.service.CommunityPolicy;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

@Repository
@RequiredArgsConstructor
public class CommunityKnowledgeSource implements KnowledgeSource {
    private final CommunityRepository repository;
    private final EntityManager entityManager;
    private final CommunityPolicy policy;
    private final KnowledgeProperties properties;

    @Override
    public String sourceType() {
        return "community";
    }

    @Override
    public List<Excerpt> retrieve(float[] vector, String question, Long guildId, int limit, String model) {
        var actor = policy.actor();
        var query = entityManager.createNativeQuery("""
            select x.chunk_id,1-(x.embedding <=> cast(:vector as vector)) similarity
            from learning_hub.community_search_chunks x
            join learning_hub.community_contents c on c.content_id=x.content_id
            where x.content_id in (
            """ + CommunityVisibleSql.IDS + """
            ) and x.revision_id=c.current_published_revision_id and x.index_status='ready'
              and x.embedding_model=:model and x.embedding is not null
              and (x.transcript_id is null or exists(select 1 from learning_hub.community_media_transcripts t
                where t.transcript_id=x.transcript_id and t.status='published'))
              and not exists(select 1 from learning_hub.community_maintenance_reviews m
                where m.revision_id=x.revision_id and m.outcome='obsolete' and m.maintenance_review_id=(
                  select max(latest.maintenance_review_id) from learning_hub.community_maintenance_reviews latest
                    where latest.revision_id=x.revision_id))
              and (:allGuilds=true or c.primary_guild_id=:guild or exists(
                select 1 from learning_hub.community_content_guilds cg where cg.content_id=c.content_id and cg.guild_id=:guild)
                or exists(select 1 from learning_hub.community_wiki_pages w where w.content_id=c.content_id and w.guild_id=:guild)
                or exists(select 1 from learning_hub.community_radar_entries re where re.content_id=c.content_id and re.guild_id=:guild))
            order by x.embedding <=> cast(:vector as vector), x.chunk_id limit :limit
            """);
        CommunityVisibleSql.parameters(actor).forEach(query::setParameter);
        query.setParameter("model", model);
        query.setParameter("vector", VectorLiteral.encode(vector));
        query.setParameter("allGuilds", guildId == null);
        query.setParameter("guild", guildId == null ? -1L : guildId);
        query.setParameter("limit", limit);
        List<?> rows = query.getResultList();
        return rows.stream().map(Object[].class::cast).map(row -> {
            var value = excerpt(repository.require(CommunitySearchChunk.class, ((Number) row[0]).longValue()));
            return new Excerpt(value.pointer(), value.title(), value.text(), value.url(), value.locator(),
                ((Number) row[1]).doubleValue());
        }).toList();
    }

    @Override
    public Excerpt resolve(Pointer pointer) {
        var chunk = repository.find(CommunitySearchChunk.class, pointer.chunkId());
        if (chunk == null || !Objects.equals(chunk.getContentId(), pointer.sourceId())
            || !Objects.equals(chunk.getRevisionId(), pointer.revisionId()) || !"ready".equals(chunk.getIndexStatus())
            || !Objects.equals(chunk.getEmbeddingModel(), properties.ollama().embedModel())) {
            return null;
        }
        var content = repository.find(CommunityContent.class, chunk.getContentId());
        if (content == null || !policy.canRead(content, policy.actor())
            || !Objects.equals(content.getCurrentPublishedRevisionId(), chunk.getRevisionId())) {
            return null;
        }
        if (chunk.getTranscriptId() != null) {
            var transcript = repository.find(MediaTranscript.class, chunk.getTranscriptId());
            if (transcript == null || !"published".equals(transcript.getStatus())) {
                return null;
            }
        }
        var reviews = repository.page("""
            select m.outcome from MaintenanceReview m where m.revisionId=:revision order by m.maintenanceReviewId desc
            """, String.class, Map.of("revision", chunk.getRevisionId()), 0, 1);
        return reviews.contains("obsolete") ? null : excerpt(chunk);
    }

    private Excerpt excerpt(CommunitySearchChunk chunk) {
        var revision = repository.require(ContentRevision.class, chunk.getRevisionId());
        Map<String, Object> locator = new HashMap<>();
        locator.put("chunkId", chunk.getChunkId());
        if (chunk.getStartSeconds() != null) {
            locator.put("startSeconds", chunk.getStartSeconds());
            locator.put("endSeconds", chunk.getEndSeconds());
        }
        return new Excerpt(new Pointer(sourceType(), chunk.getContentId(), chunk.getRevisionId(), chunk.getChunkId()),
            revision.getTitle(), chunk.getBodyText().substring(0, Math.min(2000, chunk.getBodyText().length())),
            "/community/contents/" + chunk.getContentId() + "?revisionId=" + chunk.getRevisionId(), locator, 1);
    }
}
