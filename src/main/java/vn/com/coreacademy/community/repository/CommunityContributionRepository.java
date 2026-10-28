package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.identity.service.CommunityDirectorySource;

/** Chính sách v1 dựng lại từ nguồn hiện tại; ledger chỉ append credit/reversal dưới khóa ghi dùng chung. */
@Repository
@RequiredArgsConstructor
public class CommunityContributionRepository {
    private final EntityManager entityManager;
    private final CommunityDirectorySource directory;

    private String desiredSql() {
        return """
            with people as (
            """ + directory.activePeopleSql() + """
            ), eligible as (
              select c.* from learning_hub.community_contents c
              left join learning_hub.community_answers a on a.content_id = c.content_id
              join learning_hub.community_contents root on root.content_id = coalesce(a.question_content_id, c.content_id)
              where c.owner_org_id = :visibleOrg and root.owner_org_id = :visibleOrg
                and c.current_published_revision_id is not null and root.current_published_revision_id is not null
                and c.deleted_at is null and root.deleted_at is null
                and c.lifecycle_status <> 'hidden' and root.lifecycle_status <> 'hidden'
                and root.visibility = 'organization'
                and c.author_user_id in (select user_id from people)
            ), desired as (
              select answer.content_id, answer.author_user_id user_id, 'accepted_answer'::text reason_code,
                5 points, q.author_user_id source_actor,
                (select max(e.event_id) from learning_hub.community_outbox_events e
                  where e.content_id = question.content_id and e.event_type = 'answer_accept_changed'
                    and e.actor_user_id = q.author_user_id) source_event_id, question.accepted_at occurred_at
              from learning_hub.community_questions question
              join eligible q on q.content_id = question.content_id
              join eligible answer on answer.content_id = question.accepted_answer_content_id
              where answer.author_user_id <> q.author_user_id and question.accepted_answer_revision_id is not null
              union all
              select c.content_id, c.author_user_id, 'received_upvote', 1, v.user_id,
                (select max(e.event_id) from learning_hub.community_outbox_events e
                  where e.content_id = c.content_id and e.event_type = 'vote_changed'
                    and e.actor_user_id = v.user_id), v.update_time
              from learning_hub.community_votes v join eligible c on c.content_id = v.content_id
              where v.value = 1 and v.user_id <> c.author_user_id and v.user_id in (select user_id from people)
              union all
              select c.content_id, m.reviewer_user_id, 'verified_maintenance', 3, m.reviewer_user_id,
                (select max(e.event_id) from learning_hub.community_outbox_events e
                  where e.content_id = c.content_id and e.event_type = 'maintenance_reviewed'
                    and e.actor_user_id = m.reviewer_user_id), m.reviewed_at
              from learning_hub.community_maintenance_reviews m join eligible c on c.content_id = m.content_id
              join learning_hub.community_content_revisions r on r.revision_id = m.revision_id
              where m.revision_id = c.current_published_revision_id and m.outcome = 'current'
                and m.reviewer_user_id <> c.author_user_id and m.reviewer_user_id <> c.maintainer_user_id
                and m.reviewer_user_id <> r.created_by and m.reviewer_user_id in (select user_id from people)
                and m.maintenance_review_id = (select max(latest.maintenance_review_id)
                  from learning_hub.community_maintenance_reviews latest where latest.revision_id = m.revision_id
                    and latest.reviewer_user_id = m.reviewer_user_id)
            ), current_desired as (select * from desired where source_event_id is not null)
            """;
    }

    /** Hai INSERT cùng transaction: đảo positive không còn đủ điều kiện, rồi cấp đúng grain chưa có credit active. */
    public int reconcile(Long orgId, Long contentId, Long sourceEventId, String workerId) {
        return reconcile(orgId, contentId, sourceEventId, workerId, List.of(), Instant.EPOCH,
            Instant.parse("9999-12-31T23:59:59Z"));
    }

    public int reconcile(Long orgId, Long contentId, Long sourceEventId, String workerId, List<String> users,
                          Instant from, Instant to) {
        String grain = """
            d.content_id = positive.content_id and d.user_id = positive.user_id
              and d.reason_code = positive.reason_code and d.source_actor = origin.actor_user_id
            """;
        Map<String, Object> values = new HashMap<>();
        values.put("visibleOrg", orgId);
        values.put("content", contentId);
        values.put("event", sourceEventId);
        values.put("worker", workerId);
        values.put("allBeneficiaries", users.isEmpty());
        values.put("beneficiaries", users.isEmpty() ? List.of("") : users);
        values.put("periodFrom", from);
        values.put("periodTo", to);
        int reversed = execute(desiredSql() + """
            insert into learning_hub.community_contribution_events
              (org_id,user_id,content_id,source_event_id,reason_code,points_delta,policy_version,
               reverses_event_id,occurred_at,created_by,updated_by,create_time,update_time,row_version)
            select positive.org_id,positive.user_id,positive.content_id,:event,positive.reason_code,
              -positive.points_delta,positive.policy_version,positive.contribution_event_id,now(),:worker,:worker,now(),now(),0
            from learning_hub.community_contribution_events positive
            join learning_hub.community_outbox_events origin on origin.event_id = positive.source_event_id
            where positive.org_id = :visibleOrg and positive.content_id = :content and positive.points_delta > 0
              and (:allBeneficiaries=true or positive.user_id in (:beneficiaries))
              and positive.occurred_at>=:periodFrom and positive.occurred_at<:periodTo
              and not exists (select 1 from learning_hub.community_contribution_events reversal
                where reversal.reverses_event_id = positive.contribution_event_id)
              and not exists (select 1 from current_desired d where
            """ + grain + ")", values);
        values.remove("event");
        int credits = execute(desiredSql() + """
            insert into learning_hub.community_contribution_events
              (org_id,user_id,content_id,source_event_id,reason_code,points_delta,policy_version,
               occurred_at,created_by,updated_by,create_time,update_time,row_version)
            select :visibleOrg,d.user_id,d.content_id,d.source_event_id,d.reason_code,d.points,1,
              d.occurred_at,:worker,:worker,now(),now(),0 from current_desired d
            where d.content_id = :content and (:allBeneficiaries=true or d.user_id in (:beneficiaries))
              and d.occurred_at>=:periodFrom and d.occurred_at<:periodTo and not exists (
              select 1 from learning_hub.community_contribution_events positive
              join learning_hub.community_outbox_events origin on origin.event_id = positive.source_event_id
              where positive.points_delta > 0 and positive.policy_version = 1 and
            """ + grain + """
                and not exists (select 1 from learning_hub.community_contribution_events reversal
                  where reversal.reverses_event_id = positive.contribution_event_id))
            """, values);
        return reversed + credits;
    }

    public List<Object[]> metrics(Long orgId, String userId, Instant from, Instant to, Long guildId,
                                   int offset, int limit) {
        Query query = query(desiredSql() + """
            select d.user_id, sum(d.points), count(*) filter(where d.reason_code = 'accepted_answer'),
              count(*) filter(where d.reason_code = 'received_upvote'),
              count(*) filter(where d.reason_code = 'verified_maintenance')
            from current_desired d where d.occurred_at >= :from and d.occurred_at < :to
              and (:allUsers = true or d.user_id = :user)
              and (:allGuilds = true or exists (select 1 from learning_hub.community_guild_members m
                where m.guild_id = :guild and m.user_id = d.user_id and m.status = 'active'))
            group by d.user_id order by sum(d.points) desc, d.user_id
            """, metricParameters(orgId, userId, from, to, guildId));
        query.setFirstResult(offset).setMaxResults(limit);
        return query.getResultList().stream().map(Object[].class::cast).toList();
    }

    public long metricUsers(Long orgId, Instant from, Instant to, Long guildId) {
        return ((Number) query(desiredSql() + """
            select count(distinct d.user_id) from current_desired d where d.occurred_at >= :from and d.occurred_at < :to
              and (:allUsers = true or d.user_id = :user)
              and (:allGuilds = true or exists (select 1 from learning_hub.community_guild_members m
                where m.guild_id = :guild and m.user_id = d.user_id and m.status = 'active'))
            """, metricParameters(orgId, null, from, to, guildId)).getSingleResult()).longValue();
    }

    public Map<String, Long> lifetimeMetrics(Long orgId, String userId) {
        var rows = metrics(orgId, userId, Instant.EPOCH, Instant.now().plusSeconds(1), null, 0, 1);
        if (rows.isEmpty()) {
            return Map.of("accepted_answers", 0L, "received_upvotes", 0L, "verified_maintenance", 0L);
        }
        var row = rows.get(0);
        return Map.of("accepted_answers", ((Number) row[2]).longValue(), "received_upvotes", ((Number) row[3])
            .longValue(),
            "verified_maintenance", ((Number) row[4]).longValue());
    }

    String metricSourceSql() {
        return desiredSql() + """
            select d.user_id, count(*) filter(where d.reason_code='accepted_answer') accepted_answers,
              count(*) filter(where d.reason_code='received_upvote') received_upvotes,
              count(*) filter(where d.reason_code='verified_maintenance') verified_maintenance
            from current_desired d group by d.user_id
            """;
    }

    private Map<String, Object> metricParameters(Long org, String user, Instant from, Instant to, Long guild) {
        Map<String, Object> values = new HashMap<>();
        values.put("visibleOrg", org);
        values.put("from", from);
        values.put("to", to);
        values.put("allUsers", user == null);
        values.put("user", user == null ? "" : user);
        values.put("allGuilds", guild == null);
        values.put("guild", guild == null ? -1L : guild);
        return values;
    }

    private int execute(String sql, Map<String, Object> parameters) {
        return query(sql, parameters).executeUpdate();
    }

    private Query query(String sql, Map<String, Object> parameters) {
        var query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        return query;
    }
}
