package vn.com.coreacademy.community.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.entity.Guild;
import vn.com.coreacademy.community.entity.GuildMember;
import vn.com.coreacademy.identity.service.CommunityDirectorySource;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class GuildRepository {
    private final EntityManager entityManager;
    private final CommunityRepository repository;
    private final CommunityDirectorySource directory;

    public Map<Long, Object[]> metadata(List<Long> ids, Actor actor) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var query = entityManager.createNativeQuery("""
            with visible as (
            """ + CommunityVisibleSql.IDS + """
            ), people as (
            """ + directory.activePeopleSql() + """
            ) select g.guild_id,
              (select count(*) from learning_hub.community_contents c where c.content_id in (select content_id from visible)
                and (c.primary_guild_id=g.guild_id or exists(select 1 from learning_hub.community_content_guilds cg
                  where cg.content_id=c.content_id and cg.guild_id=g.guild_id)
                  or exists(select 1 from learning_hub.community_wiki_pages w where w.content_id=c.content_id and w.guild_id=g.guild_id)
                  or exists(select 1 from learning_hub.community_radar_entries r where r.content_id=c.content_id and r.guild_id=g.guild_id))),
              case when exists(select 1 from learning_hub.community_guild_members mine where mine.guild_id=g.guild_id
                and mine.user_id=:visibleUser and mine.status='active') then (select count(*)
                  from learning_hub.community_guild_members m where m.guild_id=g.guild_id and m.status='active'
                    and m.user_id in (select user_id from people)) else 0 end,
              not exists(select 1 from people p where p.user_id=g.owner_user_id)
            from learning_hub.community_guilds g where g.guild_id in (:ids)
            """);
        CommunityVisibleSql.parameters(actor).forEach(query::setParameter);
        query.setParameter("ids", ids);
        List<?> rows = query.getResultList();
        return rows.stream().map(Object[].class::cast).collect(Collectors.toMap(
            row -> ((Number) row[0]).longValue(), Function.identity()));
    }

    public Map<Long, GuildMember> ownMemberships(List<Long> ids, String user) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return repository.list("select m from GuildMember m where m.guildId in :ids and m.userId=:user",
            GuildMember.class, Map.of("ids", ids, "user", user)).stream().collect(Collectors.toMap(
                GuildMember::getGuildId, Function.identity()));
    }

    public GuildMember membership(Long guildId, String userId) {
        var members = repository.list("""
            select m from GuildMember m where m.guildId = :guild and m.userId = :user
            """, GuildMember.class, Map.of("guild", guildId, "user", userId));
        return members.isEmpty() ? null : members.get(0);
    }

    public List<Guild> visibleForExpert(String userId, String viewerId, Long orgId) {
        return repository.list("""
            select g from Guild g where g.ownerOrgId = :org and exists (
              select m.membershipId from GuildMember m where m.guildId = g.guildId
                and m.userId = :user and m.status = :active)
              and (g.visibility = :organization or exists (
                select v.membershipId from GuildMember v where v.guildId = g.guildId
                  and v.userId = :viewer and v.status = :active))
            order by g.name, g.guildId
            """, Guild.class, Map.of("org", orgId, "user", userId, "viewer", viewerId,
                "active", CommunityCodes.ACTIVE, "organization", CommunityCodes.ORGANIZATION));
    }

    public List<Guild> request(String userId, UUID id) {
        return repository.list("""
            select g from Guild g where g.createdBy = :user and g.clientRequestId = :id
            """, Guild.class, Map.of("user", userId, "id", id));
    }

    public boolean slugUsed(Long orgId, String slug) {
        return repository.one("""
            select count(g) from Guild g where g.ownerOrgId = :org and g.slug = :slug
            """, Long.class, Map.of("org", orgId, "slug", slug)) > 0;
    }

    public Page<Guild> page(Long orgId, String userId, String q, String visibility, boolean mine, int page, int size) {
        var query = new NativeSearchQuery("from learning_hub.community_guilds g\n");
        query.and("""
            g.owner_org_id = :org
            and (g.visibility = :organization or exists (
              select 1 from learning_hub.community_guild_members m
              where m.guild_id = g.guild_id and m.user_id = :user and m.status = :active))
            """);
        query.bind("org", orgId);
        query.bind("user", userId);
        query.bind("organization", CommunityCodes.ORGANIZATION);
        query.bind("active", CommunityCodes.ACTIVE);
        if (visibility != null) {
            if (!List.of("organization", "private").contains(visibility)) {
                throw BusinessException.validation("visibility", "INVALID_VALUE", "Phạm vi Guild không hợp lệ.");
            }
            query.and("g.visibility = :visibility");
            query.bind("visibility", visibility);
        }
        if (mine) {
            query.and("""
                exists (select 1 from learning_hub.community_guild_members mine
                  where mine.guild_id = g.guild_id and mine.user_id = :user and mine.status = :active)
                """);
        }
        if (q != null && !q.isBlank()) {
            query.and("position(lower(:q) in lower(g.name || ' ' || g.slug)) > 0");
            query.bind("q", q.trim());
        }
        return query.page(entityManager, "select g.*\n", Guild.class,
            PageRequest.of(page, size, Sort.by("name", "id")), Map.of("name", "g.name", "id", "g.guild_id"));
    }

    public Page<GuildMember> members(Long guildId, String q, String status, String role, int page, int size) {
        var query = new NativeSearchQuery("from learning_hub.community_guild_members m\n");
        query.and("m.guild_id = :guild");
        query.bind("guild", guildId);
        if (q != null && !q.isBlank()) {
            query.and("position(lower(:q) in lower(m.user_id)) > 0");
            query.bind("q", q.trim());
        }
        if (status != null) {
            if (!List.of("invited", "pending", "active", "left", "rejected").contains(status)) {
                throw BusinessException.validation("status", "INVALID_VALUE", "Trạng thái thành viên không hợp lệ.");
            }
            query.and("m.status = :status");
            query.bind("status", status);
        }
        if (role != null) {
            if (!List.of("owner", "moderator", "member").contains(role)) {
                throw BusinessException.validation("role", "INVALID_VALUE", "Vai trò Guild không hợp lệ.");
            }
            query.and("m.member_role = :role");
            query.bind("role", role);
        }
        return query.page(entityManager, "select m.*\n", GuildMember.class,
            PageRequest.of(page, size, Sort.by("id")), Map.of("id", "m.membership_id"));
    }
}
