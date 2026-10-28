package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.Map;

import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

/** Subquery quyền dùng chung cho các read model; caller vẫn phải kiểm quyền thao tác tại service. */
public final class CommunityVisibleSql {
    public static final String READABLE_IDS = """
        select vc.content_id from learning_hub.community_contents vc
        left join learning_hub.community_answers va on va.content_id = vc.content_id
        join learning_hub.community_contents vp on vp.content_id = coalesce(va.question_content_id, vc.content_id)
        where vc.owner_org_id = :visibleOrg and vp.owner_org_id = :visibleOrg
          and vc.deleted_at is null and vp.deleted_at is null
          and vc.lifecycle_status <> :visibleHidden and vp.lifecycle_status <> :visibleHidden
          and (vp.visibility = :visibleOrganization
            or (vp.visibility = :visibleGuild and exists (
              select 1 from learning_hub.community_guild_members vm
              join learning_hub.community_guilds vg on vg.guild_id = vm.guild_id
              where vm.guild_id = vp.primary_guild_id and vm.user_id = :visibleUser
                and vm.status = :visibleActive and vg.owner_org_id = :visibleOrg))
            or (vp.visibility = :visibleRestricted and (vp.author_user_id = :visibleUser
              or vp.maintainer_user_id = :visibleUser or exists (
                select 1 from learning_hub.community_content_access_grants vgr
                where vgr.content_id = vp.content_id and vgr.status = :visibleActive
                  and (vgr.expires_at is null or vgr.expires_at > :visibleNow)
                  and (vgr.user_id = :visibleUser or exists (
                    select 1 from learning_hub.community_guild_members vgm
                    join learning_hub.community_guilds vgg on vgg.guild_id = vgm.guild_id
                    where vgm.guild_id = vgr.guild_id and vgm.user_id = :visibleUser
                      and vgm.status = :visibleActive and vgg.owner_org_id = :visibleOrg))))))
        """;
    public static final String IDS = READABLE_IDS + "\n and vc.current_published_revision_id is not null\n";

    public static void bind(NativeSearchQuery query, Actor actor) {
        parameters(actor).forEach(query::bind);
    }

    public static Map<String, Object> parameters(Actor actor) {
        return Map.of("visibleOrg", actor.person().orgId(), "visibleUser", actor.person().id(),
            "visibleNow", Instant.now(), "visibleHidden", CommunityCodes.HIDDEN,
            "visibleOrganization", CommunityCodes.ORGANIZATION, "visibleGuild", CommunityCodes.GUILD,
            "visibleRestricted", CommunityCodes.RESTRICTED, "visibleActive", CommunityCodes.ACTIVE);
    }

    private CommunityVisibleSql() {
    }
}
