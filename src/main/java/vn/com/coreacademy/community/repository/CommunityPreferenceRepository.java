package vn.com.coreacademy.community.repository;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.entity.CommunitySubscription;
import vn.com.coreacademy.community.entity.UserExpertise;
import vn.com.coreacademy.identity.service.CommunityDirectorySource;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityPreferenceRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;
    private final CommunityDirectorySource directory;

    public List<CommunitySubscription> subscriptions(String userId) {
        return repository.list("""
            select s from CommunitySubscription s where s.userId = :user order by s.subscriptionId
            """, CommunitySubscription.class, Map.of("user", userId));
    }

    public List<UserExpertise> expertise(String userId) {
        return repository.list("""
            select e from UserExpertise e where e.userId = :user order by e.tagId, e.expertiseId
            """, UserExpertise.class, Map.of("user", userId));
    }

    public Page<CommunitySubscription> visibleSubscriptions(Actor actor, int page, int size) {
        NativeSearchQuery query = new NativeSearchQuery("from learning_hub.community_subscriptions s\n");
        query.and("s.user_id = :visibleUser");
        query.and("""
            (s.content_id is not null and s.content_id in (
            """ + CommunityVisibleSql.IDS + """
            )) or (s.tag_id is not null and exists (
              select 1 from learning_hub.community_tags t where t.tag_id = s.tag_id
                and t.owner_org_id = :visibleOrg and t.status = :visibleActive))
            or (s.guild_id is not null and exists (
              select 1 from learning_hub.community_guilds g where g.guild_id = s.guild_id
                and g.owner_org_id = :visibleOrg and (g.visibility = :visibleOrganization or exists (
                  select 1 from learning_hub.community_guild_members m where m.guild_id = g.guild_id
                    and m.user_id = :visibleUser and m.status = :visibleActive))))
            or (s.author_user_id is not null and s.author_user_id in (select d.user_id from (
            """ + directory.activePeopleSql() + ") d))");
        CommunityVisibleSql.bind(query, actor);
        return query.page(entityManager, "select s.*\n", CommunitySubscription.class,
            PageRequest.of(page, size, Sort.by("id")), Map.of("id", "s.subscription_id"));
    }

    public Page<String> experts(Actor actor, String q, List<Long> tagIds, Long guildId, Boolean available,
                                int page, int size) {
        NativeSearchQuery query = new NativeSearchQuery("from (" + directory.activePeopleSql() + ") u\n");
        query.bind("visibleOrg", actor.person().orgId());
        if (q != null && !q.isBlank()) {
            query.and("position(lower(:q) in lower(u.full_name)) > 0");
            query.bind("q", q.trim());
        }
        query.and("""
            exists (select 1 from learning_hub.community_user_expertise e
              join learning_hub.community_tags t on t.tag_id = e.tag_id
              where e.user_id = u.user_id and t.owner_org_id = :visibleOrg and t.status = :active
                and (:anyAvailability = true or e.available_for_help = :available))
            """);
        query.bind("active", "active");
        query.bind("anyAvailability", available == null);
        query.bind("available", Boolean.TRUE.equals(available));
        if (tagIds != null && !tagIds.isEmpty()) {
            query.and("""
                exists (select 1 from learning_hub.community_user_expertise et
                  where et.user_id = u.user_id and et.tag_id in (:tags))
                """);
            query.bind("tags", tagIds);
        }
        if (guildId != null) {
            query.and("""
                exists (select 1 from learning_hub.community_guild_members m
                  where m.guild_id = :guild and m.user_id = u.user_id and m.status = :active)
                """);
            query.bind("guild", guildId);
        }
        return query.page(entityManager, "select u.user_id\n", String.class,
            PageRequest.of(page, size, Sort.by("name", "id")), Map.of("name", "u.full_name", "id", "u.user_id"));
    }
}
