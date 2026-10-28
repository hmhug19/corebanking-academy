package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.entity.CommunityModerator;

/** Các truy vấn quyền dùng cùng mã trạng thái với policy; không nhận scope từ request HTTP. */
@Repository
@RequiredArgsConstructor
public class CommunityAccessRepository {
    private final CommunityRepository repository;

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
