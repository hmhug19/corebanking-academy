package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.entity.CommunityNotification;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.CommunityUserPreferences;
import vn.com.coreacademy.community.entity.NotificationDigestBatch;
import vn.com.coreacademy.identity.service.CommunityDirectorySource;

@Repository
@RequiredArgsConstructor
public class CommunityFanoutRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;
    private final CommunityDirectorySource directory;

    public record Recipient(String userId, String mode) {
    }

    /** Scope nguồn và quyền người nhận áp trước keyset limit, không phát snapshot private cho user mất quyền. */
    public List<Recipient> recipients(CommunityOutboxEvent event, Long questionId, String after, Set<String> direct) {
        String acl = CommunityVisibleSql.IDS.replace(":visibleUser", "u.user_id");
        var query = entityManager.createNativeQuery("""
            select u.user_id,case when u.user_id in (:direct) or exists(
                select 1 from learning_hub.community_guild_members hm where hm.guild_id=:helpGuild
                  and hm.user_id=u.user_id and hm.status='active') then 'instant'
              when exists(select 1 from learning_hub.community_subscriptions s
                where s.user_id=u.user_id and s.delivery_mode='instant' and (
                  s.content_id in (:content,:question) or s.author_user_id=c.author_user_id
                  or s.guild_id=c.primary_guild_id or exists(select 1 from learning_hub.community_content_tags ct
                    where ct.content_id in (:content,:question) and ct.tag_id=s.tag_id))) then 'instant'
              else 'digest' end mode
            from (
            """ + directory.activePeopleSql() + """
            ) u left join learning_hub.community_contents c on c.content_id=:content
            where u.user_id>:after and u.user_id<>:actor and (
              (:moderation=true and u.user_id in (:direct) and u.org_id=:visibleOrg)
              or (c.content_id is not null and c.content_id in (
            """ + acl + """
            )) or (:content<0 and exists(select 1 from learning_hub.community_guilds g
              join learning_hub.community_guild_members m on m.guild_id=g.guild_id
              where g.guild_id=:guild and g.owner_org_id=:visibleOrg and m.user_id=u.user_id
                and m.status in ('active','invited'))))
              and (:moderation=false or u.user_id in (:direct))
              and (u.user_id in (:direct) or exists(select 1 from learning_hub.community_guild_members hm
                where hm.guild_id=:helpGuild and hm.user_id=u.user_id and hm.status='active')
                or exists(select 1 from learning_hub.community_subscriptions s
                where s.user_id=u.user_id and s.delivery_mode in ('instant','digest') and (
                  s.content_id in (:content,:question) or s.author_user_id=c.author_user_id
                  or s.guild_id=c.primary_guild_id or exists(select 1 from learning_hub.community_content_tags ct
                    where ct.content_id in (:content,:question) and ct.tag_id=s.tag_id))))
            order by u.user_id limit 100
            """);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("visibleOrg", event.getOwnerOrgId());
        parameters.put("visibleNow", Instant.now());
        parameters.put("visibleHidden", "hidden");
        parameters.put("visibleOrganization", "organization");
        parameters.put("visibleGuild", "guild");
        parameters.put("visibleRestricted", "restricted");
        parameters.put("visibleActive", "active");
        parameters.put("content", event.getContentId() == null ? -1L : event.getContentId());
        parameters.put("question", questionId == null ? -1L : questionId);
        parameters.put("guild", event.getGuildId() == null ? -1L : event.getGuildId());
        parameters.put("helpGuild", event.getPayloadJson().hasNonNull("targetGuildId")
            ? event.getPayloadJson().get("targetGuildId").asLong() : -1L);
        parameters.put("actor", event.getActorUserId() == null ? "" : event.getActorUserId());
        parameters.put("moderation", "content_moderated".equals(event.getEventType()));
        parameters.put("direct", direct.isEmpty() ? List.of("") : direct);
        parameters.put("after", after);
        parameters.forEach(query::setParameter);
        List<?> rows = query.getResultList();
        return rows.stream().map(Object[].class::cast).map(row -> new Recipient(row[0].toString(), row[1].toString()))
            .toList();
    }

    public boolean already(Long event, String user) {
        return repository.one("""
            select count(n) from CommunityNotification n where n.eventId=:event and n.recipientUserId=:user
            """, Long.class, Map.of("event", event, "user", user)) > 0;
    }

    public List<CommunityUserPreferences> digestDue() {
        return repository.page("""
            select p from CommunityUserPreferences p where p.digestFrequency<>'off' and p.nextDigestAt<=:now
            order by p.nextDigestAt,p.userId
            """, CommunityUserPreferences.class, Map.of("now", Instant.now()), 0, 10);
    }

    public List<CommunityNotification> unclaimedDigest(String user, Instant from, Instant to) {
        return repository.page("""
            select n from CommunityNotification n where n.recipientUserId=:user and n.deliveryMode='digest'
              and n.deliveryStatus='pending' and n.createTime>=:from and n.createTime<:to
              and not exists(select i.itemId from NotificationDigestItem i where i.notificationId=n.notificationId)
            order by n.notificationId
            """, CommunityNotification.class, Map.of("user", user, "from", from, "to", to), 0, 100);
    }

    public Instant latestWindow(String user) {
        return repository.one("select max(b.windowEnd) from NotificationDigestBatch b where b.userId=:user",
            Instant.class, Map.of("user", user));
    }

    public List<NotificationDigestBatch> pendingBatches() {
        return repository.page("""
            select b from NotificationDigestBatch b where b.batchStatus in ('pending','processing')
              and (b.leaseExpiresAt is null or b.leaseExpiresAt<=:now) order by b.batchId
            """, NotificationDigestBatch.class, Map.of("now", Instant.now()), 0, 1);
    }
}
