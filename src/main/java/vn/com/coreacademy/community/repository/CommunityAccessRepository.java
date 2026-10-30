package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.entity.CommunityModerator;
import vn.com.coreacademy.identity.service.CommunityDirectorySource;

/** Các truy vấn quyền dùng cùng mã trạng thái với policy; không nhận scope từ request HTTP. */
@Repository
@RequiredArgsConstructor
public class CommunityAccessRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;
    private final CommunityDirectorySource directory;

    public boolean member(Long guildId, String userId, Long orgId) {
        return guildId != null && repository.one("""
            select count(m) from GuildMember m join Guild g on g.guildId = m.guildId
            where m.guildId = :guild and m.userId = :user and m.status = :active and g.ownerOrgId = :org
            """, Long.class, Map.of("guild", guildId, "user", userId,
                "active", CommunityCodes.ACTIVE, "org", orgId)) > 0;
    }

    public boolean moderator(String userId, Long orgId) {
        return repository.one("""
            select count(m) from CommunityModerator m
            where m.userId = :user and m.orgId = :org and m.status = :active
            """, Long.class, Map.of("user", userId, "org", orgId, "active", CommunityCodes.ACTIVE)) > 0;
    }

    public List<CommunityModerator> moderators(String userId, Long orgId) {
        return repository.list("""
            select m from CommunityModerator m where m.userId = :user and m.orgId = :org
            """, CommunityModerator.class, Map.of("user", userId, "org", orgId));
    }

    public List<CommunityModerator> moderatorPage(Long orgId, String status, int offset, int size) {
        return repository.page("""
            select m from CommunityModerator m where m.orgId = :org and m.status = :status
            order by m.grantedAt desc, m.moderatorId desc
            """, CommunityModerator.class, Map.of("org", orgId, "status", status), offset, size);
    }

    public long moderatorCount(Long orgId, String status) {
        return repository.one("""
            select count(m) from CommunityModerator m where m.orgId = :org and m.status = :status
            """, Long.class, Map.of("org", orgId, "status", status));
    }

    /** Nhân sự đang hoạt động của đơn vị cho ô chọn người điều phối/người duy trì; chỉ userId và họ tên. */
    public List<Object[]> staffPage(Long orgId, String q, int offset, int size) {
        var query = entityManager.createNativeQuery("select p.user_id, p.full_name from (" + directory.activePeopleSql()
            + ") p where (:q = '' or position(lower(:q) in lower(p.full_name)) > 0)"
            + " order by p.full_name, p.user_id offset :offset limit :size");
        query.setParameter("visibleOrg", orgId);
        query.setParameter("q", q == null ? "" : q.trim());
        query.setParameter("offset", offset);
        query.setParameter("size", size);
        return query.getResultList().stream().map(Object[].class::cast).toList();
    }

    public long staffCount(Long orgId, String q) {
        var query = entityManager.createNativeQuery("select count(*) from (" + directory.activePeopleSql()
            + ") p where (:q = '' or position(lower(:q) in lower(p.full_name)) > 0)");
        query.setParameter("visibleOrg", orgId);
        query.setParameter("q", q == null ? "" : q.trim());
        return ((Number) query.getSingleResult()).longValue();
    }

    public boolean grant(Long contentId, String userId, Long orgId, boolean editor) {
        return repository.one("""
            select count(a) from ContentAccessGrant a
            where a.contentId = :content and a.status = :active
              and (a.expiresAt is null or a.expiresAt > :now)
              and (:editor = false or a.permission = :permission)
              and (a.userId = :user or exists (
                  select m.membershipId from GuildMember m join Guild g on g.guildId = m.guildId
                  where m.guildId = a.guildId and m.userId = :user and m.status = :active and g.ownerOrgId = :org))
            """, Long.class, Map.of("content", contentId, "active", CommunityCodes.ACTIVE,
                "now", Instant.now(), "editor", editor, "permission", CommunityCodes.EDITOR,
                "user", userId, "org", orgId)) > 0;
    }
}
