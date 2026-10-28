package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.search.SearchContributor;
import vn.com.coreacademy.community.service.CommunityPolicy;

@Repository
@RequiredArgsConstructor
public class CommunitySearchContributor implements SearchContributor {
    private final CommunityPolicy policy;

    @Override
    public String sourceType() {
        return "community";
    }

    @Override
    public Part contribute(Filter filter) {
        var actor = policy.actor();
        Map<String, Object> values = new HashMap<>();
        values.put("visibleOrg", actor.person().orgId());
        values.put("visibleUser", actor.person().id());
        values.put("visibleNow", Instant.now());
        values.put("visibleHidden", "hidden");
        values.put("visibleOrganization", "organization");
        values.put("visibleGuild", "guild");
        values.put("visibleRestricted", "restricted");
        values.put("visibleActive", "active");
        values.put("communityQuery", filter.q());
        values.put("communityAnyGuild", filter.guildId() == null);
        values.put("communityGuild", filter.guildId() == null ? -1L : filter.guildId());
        values.put("communityAnyKind", filter.kind() == null);
        values.put("communityKind", filter.kind() == null ? "" : filter.kind());
        values.put("communityAnyTags", filter.tagIds() == null || filter.tagIds().isEmpty());
        values.put("communityTags", filter.tagIds() == null || filter.tagIds().isEmpty() ? List.of(-1L)
            : filter.tagIds());
        return new Part(sourceType(), """
            select distinct on (coalesce(a.question_content_id, c.content_id)) 'community'::text source_type,
              coalesce(a.question_content_id, c.content_id) source_id, root.current_published_revision_id revision_id,
              rr.title, left(r.body_markdown, 400) snippet,
              cast(ts_rank_cd(to_tsvector('simple', r.title || ' ' || r.body_markdown),
                plainto_tsquery('simple', :communityQuery), 32) as double precision) score,
              extract(epoch from c.update_time) updated_epoch,
              '/community/contents/' || root.content_id || case when a.content_id is null then ''
                else '#answer-' || a.content_id end url, a.content_id answer_id
            from learning_hub.community_contents c
            join learning_hub.community_content_revisions r on r.revision_id = c.current_published_revision_id
            left join learning_hub.community_answers a on a.content_id = c.content_id
            join learning_hub.community_contents root on root.content_id = coalesce(a.question_content_id, c.content_id)
            join learning_hub.community_content_revisions rr on rr.revision_id = root.current_published_revision_id
            where c.content_id in (
            """ + CommunityVisibleSql.IDS + """
            ) and r.deleted_at is null and r.revision_status = 'published'
              and (to_tsvector('simple', r.title || ' ' || r.body_markdown)
                @@ plainto_tsquery('simple', :communityQuery)
                or position(lower(:communityQuery) in lower(r.title || ' ' || r.body_markdown)) > 0)
              and (:communityAnyKind = true or root.kind = :communityKind)
              and (:communityAnyGuild = true or root.primary_guild_id = :communityGuild or exists (
                select 1 from learning_hub.community_content_guilds cg
                  where cg.content_id = root.content_id and cg.guild_id = :communityGuild)
                or exists (select 1 from learning_hub.community_wiki_pages w
                  where w.content_id = root.content_id and w.guild_id = :communityGuild)
                or exists (select 1 from learning_hub.community_radar_entries re
                  where re.content_id = root.content_id and re.guild_id = :communityGuild))
              and (:communityAnyTags = true or exists (select 1 from learning_hub.community_content_tags ct
                where ct.content_id = root.content_id and ct.tag_id in (:communityTags)))
            order by coalesce(a.question_content_id, c.content_id), score desc, c.content_id
            """, values);
    }
}
