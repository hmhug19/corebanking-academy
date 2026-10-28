package vn.com.coreacademy.community.repository;

/** Subjects khai báo ổn định theo grant/Guild, cùng semantics với CommunityAudience, không snapshot membership. */
final class CommunityAudienceSql {
    private static final String READERS = """
        select c.content_id,c.author_user_id user_id,null::bigint guild_id,null::timestamptz expires_at
          from learning_hub.community_contents c where c.visibility='restricted'
        union all select c.content_id,c.maintainer_user_id,null::bigint,null::timestamptz
          from learning_hub.community_contents c where c.visibility='restricted'
        union all select g.content_id,g.user_id,g.guild_id,g.expires_at
          from learning_hub.community_content_access_grants g join learning_hub.community_contents c
            on c.content_id=g.content_id where c.visibility='restricted' and g.status='active'
              and (g.expires_at is null or g.expires_at>:now)
        union all select c.content_id,null::varchar,c.primary_guild_id,null::timestamptz
          from learning_hub.community_contents c where c.visibility='guild'
        """;

    static final String EFFECTIVE_REQUEST_STATUS = """
        case when kr.request_status='fulfilled' and not exists (
          select 1 from learning_hub.community_content_links fulfillment
          join learning_hub.community_contents target on target.content_id=fulfillment.target_content_id
          left join learning_hub.community_answers ta on ta.content_id=target.content_id
          join learning_hub.community_contents target_root
            on target_root.content_id=coalesce(ta.question_content_id,target.content_id)
          where fulfillment.source_content_id=c.content_id and fulfillment.relation_type='fulfills'
            and target.content_id<>c.content_id and target.owner_org_id=c.owner_org_id
            and target_root.owner_org_id=c.owner_org_id and target.deleted_at is null and target_root.deleted_at is null
            and target.lifecycle_status<>'hidden' and target_root.lifecycle_status<>'hidden'
            and target.current_published_revision_id is not null and target_root.current_published_revision_id is not null
            and (target_root.visibility='organization' or (c.visibility<>'organization' and not exists (
              select 1 from (
        """ + READERS + """
              ) source_reader where source_reader.content_id=c.content_id and not exists (
                select 1 from (
        """ + READERS + """
                ) target_reader where target_reader.content_id=target_root.content_id
                  and target_reader.user_id is not distinct from source_reader.user_id
                  and target_reader.guild_id is not distinct from source_reader.guild_id
                  and (target_reader.expires_at is null or (source_reader.expires_at is not null
                    and target_reader.expires_at>=source_reader.expires_at))))))
        ) then case when kr.claimed_by is null then 'open' else 'claimed' end else kr.request_status end
        """;

    private CommunityAudienceSql() {
    }
}
