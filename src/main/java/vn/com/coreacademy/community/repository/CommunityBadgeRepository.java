package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.entity.BadgeAward;
import vn.com.coreacademy.community.entity.CommunityBadge;
import vn.com.coreacademy.community.entity.ContributionEvent;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityBadgeRepository {
    private final CommunityRepository repository;
    private final CommunityContributionRepository contributions;
    private final EntityManager entityManager;

    public Page<CommunityBadge> definitions(Long org, String q, String status, int page, int size) {
        var query = new NativeSearchQuery("from learning_hub.community_badges b\n");
        query.and("b.owner_org_id = :org");
        query.bind("org", org);
        if (q != null && !q.isBlank()) {
            query.and("position(lower(:q) in lower(b.code || ' ' || b.name)) > 0");
            query.bind("q", q.trim());
        }
        if (status != null) {
            query.and("b.status = :status");
            query.bind("status", status);
        }
        return query.page(entityManager, "select b.*\n", CommunityBadge.class,
            PageRequest.of(page, size, Sort.by("id")), Map.of("id", "b.badge_id"));
    }

    public boolean codeUsed(Long org, String code, int version) {
        return repository.one("""
            select count(b) from CommunityBadge b where b.ownerOrgId = :org and lower(b.code) = lower(:code)
              and b.ruleVersion = :version
            """, Long.class, Map.of("org", org, "code", code, "version", version)) > 0;
    }

    public Page<ContributionEvent> ledger(Actor actor, Instant from, Instant to, int page, int size) {
        var query = new NativeSearchQuery("from learning_hub.community_contribution_events e\n");
        query.and("""
            e.user_id = :visibleUser and e.org_id = :visibleOrg
              and e.occurred_at >= :from and e.occurred_at < :to
            """);
        query.and("e.content_id in (" + CommunityVisibleSql.IDS + ")");
        CommunityVisibleSql.bind(query, actor);
        query.bind("from", from);
        query.bind("to", to);
        return query.page(entityManager, "select e.*\n", ContributionEvent.class,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")), Map.of("id", "e.contribution_event_id"));
    }

    public Page<BadgeAward> publicAwards(Long org, String user, int page, int size) {
        var query = new NativeSearchQuery("""
            from learning_hub.community_badge_awards a
            join learning_hub.community_badges b on b.badge_id = a.badge_id
            join (
            """ + contributions.metricSourceSql() + """
            ) metrics on metrics.user_id = a.user_id
            """);
        query.and("a.user_id = :awardUser and a.status = 'active' and b.owner_org_id = :visibleOrg");
        query.and(qualifies());
        query.bind("awardUser", user);
        query.bind("visibleOrg", org);
        return query.page(entityManager, "select a.*\n", BadgeAward.class,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")), Map.of("id", "a.award_id"));
    }

    private String qualifies() {
        return """
            case b.rule_json ->> 'metric' when 'accepted_answers' then metrics.accepted_answers
              when 'received_upvotes' then metrics.received_upvotes
              when 'verified_maintenance' then metrics.verified_maintenance else 0 end
              >= cast(b.rule_json ->> 'threshold' as bigint)
            """;
    }

    public int reconcileAwards(Long org, Long event, String worker) {
        String from = " from (" + contributions.metricSourceSql() + ") metrics ";
        String eligible = "exists (select 1" + from + "where metrics.user_id = a.user_id and " + qualifies() + ")";
        int revoked = execute("""
            update learning_hub.community_badge_awards a set status='revoked', revoked_at=now(),
              revoke_reason='Nguồn công khai không còn đủ điều kiện.', update_time=now(), updated_by=:worker,
              row_version=a.row_version+1, source_event_id=:event
            from learning_hub.community_badges b where b.badge_id=a.badge_id and b.owner_org_id=:visibleOrg
              and a.status='active' and not (
            """ + eligible + ")", org, event, worker);
        int reactivated = execute("""
            update learning_hub.community_badge_awards a set status='active', awarded_at=now(), revoked_at=null,
              revoke_reason=null, update_time=now(), updated_by=:worker, row_version=a.row_version+1, source_event_id=:event
            from learning_hub.community_badges b where b.badge_id=a.badge_id and b.owner_org_id=:visibleOrg
              and b.status='active' and a.status='revoked' and (
            """ + eligible + ")", org, event, worker);
        int created = execute("""
            insert into learning_hub.community_badge_awards
              (badge_id,user_id,status,awarded_at,source_event_id,created_by,updated_by,create_time,update_time,row_version)
            select b.badge_id,metrics.user_id,'active',now(),:event,:worker,:worker,now(),now(),0
            """ + from + """
            cross join learning_hub.community_badges b where b.owner_org_id=:visibleOrg and b.status='active'
              and not exists(select 1 from learning_hub.community_badge_awards a
                where a.badge_id=b.badge_id and a.user_id=metrics.user_id) and (
            """ + qualifies() + ")", org, event, worker);
        return revoked + reactivated + created;
    }

    private int execute(String sql, Long org, Long event, String worker) {
        return entityManager.createNativeQuery(sql).setParameter("visibleOrg", org).setParameter("event", event)
            .setParameter("worker", worker).executeUpdate();
    }
}
