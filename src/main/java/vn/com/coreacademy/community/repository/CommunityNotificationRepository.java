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
import vn.com.coreacademy.community.entity.CommunityNotification;
import vn.com.coreacademy.community.entity.NotificationDigestBatch;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityNotificationRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;

    public Page<CommunityNotification> visible(Actor actor, boolean unread, Long batchId, int page, int size) {
        var query = visibleQuery(actor, unread, batchId);
        return query.page(entityManager, "select n.*\n", CommunityNotification.class,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")), Map.of("id", "n.notification_id"));
    }

    private NativeSearchQuery visibleQuery(Actor actor, boolean unread, Long batchId) {
        var query = new NativeSearchQuery("""
            from learning_hub.community_notifications n
            join learning_hub.community_outbox_events e on e.event_id = n.event_id
            """);
        query.and("n.recipient_user_id = :visibleUser and e.owner_org_id = :visibleOrg");
        query.and("""
            e.event_type='content_moderated' or (n.content_id is not null and n.content_id in (
            """ + CommunityVisibleSql.IDS + """
            )) or (n.content_id is null and e.guild_id is not null and exists (
              select 1 from learning_hub.community_guilds g where g.guild_id = e.guild_id
                and g.owner_org_id = :visibleOrg and (g.visibility = :visibleOrganization or exists (
                  select 1 from learning_hub.community_guild_members m where m.guild_id = g.guild_id
                    and m.user_id = :visibleUser and m.status in (:visibleActive, 'invited')))))
            """);
        query.and("n.delivery_status = :delivered");
        query.bind("delivered", "delivered");
        if (unread) {
            query.and("n.read_at is null");
        }
        if (batchId != null) {
            query.and("""
                exists (select 1 from learning_hub.community_notification_digest_items i
                  where i.notification_id = n.notification_id and i.batch_id = :batch)
                """);
            query.bind("batch", batchId);
        }
        CommunityVisibleSql.bind(query, actor);
        return query;
    }

    public long visibleCount(Actor actor, Long batchId, boolean unread) {
        return visibleQuery(actor, unread, batchId).page(entityManager, "select n.*\n", CommunityNotification.class,
            PageRequest.of(0, 1, Sort.by("id")), Map.of("id", "n.notification_id")).getTotalElements();
    }

    public boolean isVisible(Long notificationId, Actor actor) {
        var query = visibleQuery(actor, false, null);
        query.and("n.notification_id = :notification");
        query.bind("notification", notificationId);
        return !query.page(entityManager, "select n.*\n", CommunityNotification.class,
            PageRequest.of(0, 1, Sort.by("id")), Map.of("id", "n.notification_id")).isEmpty();
    }

    public Page<NotificationDigestBatch> batches(Actor actor, int page, int size) {
        var query = new NativeSearchQuery("from learning_hub.community_notification_digest_batches b\n");
        query.and("b.user_id = :user");
        query.bind("user", actor.person().id());
        return query.page(entityManager, "select b.*\n", NotificationDigestBatch.class,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")), Map.of("id", "b.batch_id"));
    }

    public List<CommunityNotification> ownBatchItems(Long batchId, String userId) {
        return repository.list("""
            select n from CommunityNotification n where n.recipientUserId = :user and exists (
              select i.itemId from NotificationDigestItem i where i.batchId = :batch
                and i.notificationId = n.notificationId) order by n.notificationId
            """, CommunityNotification.class, Map.of("batch", batchId, "user", userId));
    }
}
