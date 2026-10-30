package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.entity.CollectionAccessGrant;
import vn.com.coreacademy.community.entity.CollectionItem;
import vn.com.coreacademy.community.entity.CommunityCollection;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityCollectionRepository {
    private static final String ACCESS = """
        k.owner_org_id = :org and k.status = :active and (k.owner_user_id = :user
          or k.visibility = :organization
          or (k.visibility = :guildVisibility and exists (
            select 1 from learning_hub.community_guild_members m
            join learning_hub.community_guilds g on g.guild_id = m.guild_id
            where m.guild_id = k.primary_guild_id and m.user_id = :user and m.status = :active
              and g.owner_org_id = :org))
          or (k.visibility = :restricted and exists (
            select 1 from learning_hub.community_collection_access_grants a
            where a.collection_id = k.collection_id and a.status = :active
              and (a.expires_at is null or a.expires_at > :now)
              and (a.user_id = :user or exists (
                select 1 from learning_hub.community_guild_members gm
                join learning_hub.community_guilds gg on gg.guild_id = gm.guild_id
                where gm.guild_id = a.guild_id and gm.user_id = :user and gm.status = :active
                  and gg.owner_org_id = :org)))))
        """;
    private final EntityManager entityManager;
    private final CommunityRepository repository;

    public List<CommunityCollection> request(String actor, UUID request) {
        return repository.list("""
            select k from CommunityCollection k where k.createdBy = :actor and k.clientRequestId = :request
            """, CommunityCollection.class, Map.of("actor", actor, "request", request));
    }

    public CommunityCollection defaultCollection(String owner) {
        var values = repository.list("""
            select k from CommunityCollection k where k.ownerUserId = :owner and k.name = :name
            """, CommunityCollection.class, Map.of("owner", owner, "name", "Đã lưu"));
        return values.isEmpty() ? null : values.get(0);
    }

    public List<CollectionAccessGrant> grants(Long collectionId) {
        return repository.list("""
            select g from CollectionAccessGrant g where g.collectionId = :collection and g.status = :active
            order by g.grantId
            """, CollectionAccessGrant.class, Map.of("collection", collectionId, "active", "active"));
    }

    /** Nội dung có trong bộ sưu tập "Đã lưu" của người dùng không; một truy vấn thay cho collection rồi item. */
    public boolean saved(String owner, Long contentId) {
        return repository.one("""
            select count(i) from CollectionItem i, CommunityCollection k
            where k.collectionId = i.collectionId and k.ownerUserId = :owner and k.name = :name
              and i.contentId = :content
            """, Long.class, Map.of("owner", owner, "name", "Đã lưu", "content", contentId)) > 0;
    }

    public CollectionItem item(Long collectionId, Long contentId) {
        var items = repository.list("""
            select i from CollectionItem i where i.collectionId = :collection and i.contentId = :content
            """, CollectionItem.class, Map.of("collection", collectionId, "content", contentId));
        return items.isEmpty() ? null : items.get(0);
    }

    public boolean accessible(Long id, Actor actor) {
        var query = collectionQuery(actor);
        query.and("k.collection_id = :id");
        query.bind("id", id);
        return !query.page(entityManager, "select k.*\n", CommunityCollection.class,
            PageRequest.of(0, 1, Sort.by("id")), Map.of("id", "k.collection_id")).isEmpty();
    }

    public Page<CommunityCollection> list(Actor actor, String q, boolean mine, String visibility, int page, int size) {
        var query = collectionQuery(actor);
        if (mine) {
            query.and("k.owner_user_id = :user");
        }
        if (q != null && !q.isBlank()) {
            query.and("position(lower(:q) in lower(k.name)) > 0");
            query.bind("q", q.trim());
        }
        if (visibility != null) {
            query.and("k.visibility = :visibility");
            query.bind("visibility", visibility);
        }
        return query.page(entityManager, "select k.*\n", CommunityCollection.class,
            PageRequest.of(page, size, Sort.by("name", "id")), Map.of("name", "k.name", "id", "k.collection_id"));
    }

    public Page<CollectionItem> items(Long collectionId, Actor actor, int page, int size) {
        NativeSearchQuery query = new NativeSearchQuery("from learning_hub.community_collection_items i\n");
        query.and("i.collection_id = :collection");
        query.bind("collection", collectionId);
        query.and("i.content_id in (" + CommunityVisibleSql.IDS + ")");
        CommunityVisibleSql.bind(query, actor);
        return query.page(entityManager, "select i.*\n", CollectionItem.class,
            PageRequest.of(page, size, Sort.by("sort", "id")), Map.of("sort", "i.sort_no", "id", "i.item_id"));
    }

    private NativeSearchQuery collectionQuery(Actor actor) {
        var query = new NativeSearchQuery("from learning_hub.community_collections k\n");
        query.and(ACCESS);
        query.bind("org", actor.person().orgId());
        query.bind("user", actor.person().id());
        query.bind("now", Instant.now());
        query.bind("active", "active");
        query.bind("organization", "organization");
        query.bind("guildVisibility", "guild");
        query.bind("restricted", "restricted");
        return query;
    }
}
