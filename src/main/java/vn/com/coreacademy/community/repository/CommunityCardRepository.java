package vn.com.coreacademy.community.repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.identity.service.CommunityDirectorySource;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityCardRepository {
    private final EntityManager entityManager;
    private final CommunityDirectorySource directory;

    /**
     * Một query bổ sung cho cả trang: tags, vote, accept, duplicate, khả năng thao tác, bản nháp đang chờ và ngày sự
     * cố của bài học, không query từng card.
     */
    public Map<Long, Object[]> extras(List<Long> ids, Actor actor) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var query = entityManager.createNativeQuery("""
            with visible as (
            """ + CommunityVisibleSql.IDS + """
            ), people as (
            """ + directory.activePeopleSql() + """
            ) select c.content_id, coalesce(votes.score,0),coalesce(votes.mine,0),q.resolution_status,
              coalesce(q.accepted_answer_content_id,pq.accepted_answer_content_id),
              coalesce(q.accepted_answer_revision_id,pq.accepted_answer_revision_id),
              (select count(*) from learning_hub.community_answers ans join learning_hub.community_contents answer
                on answer.content_id=ans.content_id where ans.question_content_id=c.content_id
                  and answer.deleted_at is null and answer.lifecycle_status<>'hidden'
                  and answer.current_published_revision_id is not null),
              a.question_content_id,
              coalesce(pq.accepted_answer_content_id=c.content_id,false),
              coalesce(pq.accepted_answer_revision_id=c.current_published_revision_id,false),
              exists(select 1 from learning_hub.community_question_links l
                where l.source_question_id=c.content_id and l.relation_type='duplicate'),
              duplicate.content_id,duplicate.kind,duplicate.current_published_revision_id,duplicate_revision.title,
              cast(coalesce((select jsonb_agg(jsonb_build_object('tagId',t.tag_id,'slug',t.slug,'name',t.name,
                'description',t.description,'status',t.status,'version',t.row_version,'mergedIntoTagId',t.merged_into_tag_id,
                'createTime',t.create_time,'updateTime',t.update_time,'usageCount',(select count(distinct usage.content_id)
                  from learning_hub.community_content_tags usage where usage.tag_id=t.tag_id
                    and usage.content_id in (select content_id from visible)),
                'aliases',coalesce((select jsonb_agg(ta.normalized_alias order by ta.normalized_alias)
                  from learning_hub.community_tag_aliases ta where ta.tag_id=t.tag_id),'[]'::jsonb)) order by t.tag_id)
                from learning_hub.community_content_tags ct join learning_hub.community_tags t on t.tag_id=ct.tag_id
                  where ct.content_id=coalesce(a.question_content_id,c.content_id)),'[]'::jsonb) as text),
              (c.author_user_id=:visibleUser or c.maintainer_user_id=:visibleUser or exists (
                select 1 from learning_hub.community_content_access_grants ag where ag.content_id=c.content_id
                  and ag.status='active' and ag.permission='editor' and (ag.expires_at is null or ag.expires_at>:visibleNow)
                  and (ag.user_id=:visibleUser or exists (select 1 from learning_hub.community_guild_members gm
                    join learning_hub.community_guilds gg on gg.guild_id=gm.guild_id
                    where gm.guild_id=ag.guild_id and gm.user_id=:visibleUser and gm.status='active'
                      and gg.owner_org_id=:visibleOrg)))),
              (c.lifecycle_status='active' and coalesce(parent.lifecycle_status,'active')='active'
                and coalesce(proposal.review_status,'draft') not in ('concluded','closed')
                and coalesce(kr.request_status,'open')<>'closed'
                and (g.guild_id is null or (g.status='active' and exists (
                  select 1 from people p where p.user_id=g.owner_user_id)))),
              (c.author_user_id=:visibleUser or c.maintainer_user_id=:visibleUser),
              c.current_draft_revision_id is not null,
              (select r.context_json->'lessonLearned'->>'occurredOn' from learning_hub.community_content_revisions r
                where r.revision_id=c.current_published_revision_id),
              (select ra.revision_asset_id from learning_hub.community_revision_assets ra
                join learning_hub.community_assets image on image.asset_id=ra.asset_id
                where ra.revision_id=coalesce(c.current_published_revision_id,c.current_draft_revision_id)
                  and ra.usage='cover' and image.media_type='image' and image.status='ready'
                  and image.deleted_at is null and image.media_checked_at is not null
                  and image.cleanup_status<>'deleting'
                order by ra.sort_no,ra.revision_asset_id limit 1)
            from learning_hub.community_contents c
            left join learning_hub.community_answers a on a.content_id=c.content_id
            left join learning_hub.community_contents parent on parent.content_id=a.question_content_id
            left join learning_hub.community_questions q on q.content_id=c.content_id
            left join learning_hub.community_questions pq on pq.content_id=a.question_content_id
            left join learning_hub.community_review_proposals proposal on proposal.content_id=c.content_id
            left join learning_hub.community_knowledge_requests kr on kr.content_id=c.content_id
            left join learning_hub.community_wiki_pages wiki on wiki.content_id=c.content_id
            left join learning_hub.community_radar_entries radar on radar.content_id=c.content_id
            left join learning_hub.community_guilds g on g.guild_id=coalesce(c.primary_guild_id,wiki.guild_id,radar.guild_id)
            left join lateral (select sum(v.value) score, max(v.value) filter(where v.user_id=:visibleUser) mine
              from learning_hub.community_votes v where v.content_id=c.content_id) votes on true
            left join lateral (select target.* from learning_hub.community_question_links l
              join learning_hub.community_contents target on target.content_id=l.target_question_id
              where l.source_question_id=c.content_id and l.relation_type='duplicate'
                and target.content_id in (select content_id from visible) order by l.question_link_id limit 1) duplicate on true
            left join learning_hub.community_content_revisions duplicate_revision
              on duplicate_revision.revision_id=duplicate.current_published_revision_id
            where c.content_id in (:ids)
            """);
        CommunityVisibleSql.parameters(actor).forEach(query::setParameter);
        query.setParameter("ids", ids);
        List<?> rows = query.getResultList();
        return rows.stream().map(Object[].class::cast)
            .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), Function.identity()));
    }
}
