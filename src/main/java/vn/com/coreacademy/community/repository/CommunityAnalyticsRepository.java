package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.entity.ContentView;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityAnalyticsRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;

    private static final String SOURCE = """
        with visible as (
        """ + CommunityVisibleSql.IDS + """
        ), source as (
          select c.*, first_publication.published_at as first_published_at
          from learning_hub.community_contents c
          join visible v on v.content_id = c.content_id
          cross join lateral (select min(r.published_at) published_at
            from learning_hub.community_content_revisions r where r.content_id = c.content_id
              and r.revision_status in ('published', 'superseded') and r.deleted_at is null) first_publication
          where (:allGuilds = true or c.primary_guild_id = :guild or exists (
            select 1 from learning_hub.community_content_guilds cg
              where cg.content_id = c.content_id and cg.guild_id = :guild)
            or exists (select 1 from learning_hub.community_wiki_pages w
              where w.content_id = c.content_id and w.guild_id = :guild)
            or exists (select 1 from learning_hub.community_radar_entries r
              where r.content_id = c.content_id and r.guild_id = :guild))
        ), period_source as (
          select * from source where first_published_at >= :periodFrom and first_published_at < :periodTo
        )
        """;

    public Object[] overview(Actor actor, Instant from, Instant to, Long guildId) {
        Query query = query(SOURCE + """
            select count(*) filter(where q.kind = 'question'),
              count(*) filter(where q.kind = 'question' and answers.first_answer is null),
              count(*) filter(where q.kind = 'question' and answers.first_answer is not null),
              count(*) filter(where q.kind = 'question' and question.resolution_status = 'solved'),
              percentile_cont(0.5) within group (order by extract(epoch from
                (answers.first_answer - q.first_published_at)) * 1000)
                filter(where q.kind = 'question' and answers.first_answer >= q.first_published_at),
              count(distinct q.author_user_id),
              (select count(*) from learning_hub.community_content_views cv join source s
                on s.content_id = cv.content_id where cv.first_view_at >= :periodFrom and cv.first_view_at < :periodTo),
              (select count(*) from source s where exists (
                select 1 from learning_hub.community_wiki_pages w where w.content_id = s.content_id
                  and w.review_due_at <= current_date) or exists (
                select 1 from learning_hub.community_radar_entries r where r.content_id = s.content_id
                  and r.review_due_at <= current_date) or (
                select m.outcome from learning_hub.community_maintenance_reviews m
                  where m.content_id = s.content_id order by m.reviewed_at desc, m.maintenance_review_id desc limit 1
                ) in ('obsolete', 'needs_update'))
            from period_source q
            left join learning_hub.community_questions question on question.content_id = q.content_id
            left join lateral (select min(s.first_published_at) first_answer
              from learning_hub.community_answers a join source s on s.content_id = a.content_id
              where a.question_content_id = q.content_id) answers on true
            """, actor, from, to, guildId);
        return (Object[]) query.getSingleResult();
    }

    public List<Object[]> activity(Actor actor, Instant from, Instant to, Long guildId, String bucket) {
        Query query = query(SOURCE + """
            , buckets as (
              select generate_series(date_trunc(:bucket, cast(:periodFrom as timestamptz) at time zone 'UTC'),
                date_trunc(:bucket, (cast(:periodTo as timestamptz) - interval '1 microsecond') at time zone 'UTC'),
                cast(:step as interval)) as starts_at
            )
            select extract(epoch from b.starts_at at time zone 'UTC'),
              extract(epoch from (b.starts_at + cast(:step as interval)) at time zone 'UTC'),
              count(*) filter(where s.kind = 'question'), count(*) filter(where s.kind = 'article'),
              count(*) filter(where s.kind = 'answer'), count(distinct s.author_user_id),
              (select count(*) from learning_hub.community_content_views cv join source vs
                on vs.content_id = cv.content_id where cv.first_view_at >= :periodFrom and cv.first_view_at < :periodTo
                  and cv.first_view_at >= b.starts_at at time zone 'UTC'
                  and cv.first_view_at < (b.starts_at + cast(:step as interval)) at time zone 'UTC')
            from buckets b left join period_source s
              on s.first_published_at >= b.starts_at at time zone 'UTC'
                and s.first_published_at < (b.starts_at + cast(:step as interval)) at time zone 'UTC'
            group by b.starts_at order by b.starts_at
            """, actor, from, to, guildId);
        query.setParameter("bucket", bucket);
        query.setParameter("step", "week".equals(bucket) ? "7 days" : "1 day");
        return query.getResultList().stream().map(Object[].class::cast).toList();
    }

    private Query query(String sql, Actor actor, Instant from, Instant to, Long guildId) {
        Query query = entityManager.createNativeQuery(sql);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("visibleOrg", actor.person().orgId());
        parameters.put("visibleUser", actor.person().id());
        parameters.put("visibleNow", Instant.now());
        parameters.put("visibleHidden", "hidden");
        parameters.put("visibleOrganization", "organization");
        parameters.put("visibleGuild", "guild");
        parameters.put("visibleRestricted", "restricted");
        parameters.put("visibleActive", "active");
        parameters.put("periodFrom", from);
        parameters.put("periodTo", to);
        parameters.put("allGuilds", guildId == null);
        parameters.put("guild", guildId == null ? -1L : guildId);
        parameters.forEach(query::setParameter);
        return query;
    }

    public List<ContentView> view(Long contentId, String userId, LocalDate date) {
        return repository.list("""
            select v from ContentView v where v.contentId = :content and v.userId = :user and v.viewDate = :date
            """, ContentView.class, Map.of("content", contentId, "user", userId, "date", date));
    }
}
