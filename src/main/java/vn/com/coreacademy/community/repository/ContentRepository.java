package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.constants.CommunityLimits;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.identity.service.CommunityDirectorySource;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class ContentRepository {
    private final EntityManager entityManager;
    private final CommunityRepository repository;
    private final CommunityDirectorySource directory;

    public List<CommunityContent> publishedBatch(List<Long> ids,
        Actor actor) {
        if (ids.isEmpty()) {
            return List.of();
        }
        var query = entityManager.createNativeQuery("select c.* from learning_hub.community_contents c "
            + "where c.content_id in (:ids) and c.content_id in (" + CommunityVisibleSql.IDS + ")",
                CommunityContent.class);
        CommunityVisibleSql.parameters(actor).forEach(query::setParameter);
        query.setParameter("ids", ids);
        List<?> result = query.getResultList();
        return result.stream().map(CommunityContent.class::cast).toList();
    }

    public record Search(String kind, String q, List<Long> tagIds, String resolutionStatus, Long questionId,
                         boolean mine, String publicationState, String lifecycleStatus, int page, int size,
                         String sort, Long guildId, DocumentFilter documentFilter) {
        public Search(String kind, String q, List<Long> tagIds, String resolutionStatus, Long questionId,
                      boolean mine, String publicationState, String lifecycleStatus, int page, int size,
                      String sort, Long guildId) {
            this(kind, q, tagIds, resolutionStatus, questionId, mine, publicationState, lifecycleStatus,
                page, size, sort, guildId, null);
        }
        public Search(String kind, String q, List<Long> tagIds, String resolutionStatus, Long questionId,
                      boolean mine, String publicationState, String lifecycleStatus, int page, int size, String sort) {
            this(kind, q, tagIds, resolutionStatus, questionId, mine, publicationState, lifecycleStatus,
                page, size, sort, null);
        }
    }

    public record DocumentFilter(Long parentPageId, boolean rootPages, String radarStage, LocalDate dueBefore,
                                  String reviewStatus, String requestStatus, String speaker, Instant heldFrom,
                                  Instant heldTo, Integer olderThanDays, boolean followingOnly) {
    }

    /** ACL áp dụng cho parent answer trước count/limit; không để trang rỗng do lọc quyền trong RAM. */
    public Page<CommunityContent> search(Search input, String userId, Long orgId) {
        return search(input, userId, orgId, false);
    }

    public Page<CommunityContent> search(Search input, String userId, Long orgId, boolean publisherOnly) {
        if (input.page() < 0 || input.size() < 1 || input.size() > CommunityLimits.PAGE_SIZE
            || (long) input.page() * input.size() > Integer.MAX_VALUE) {
            throw BusinessException.validation("page", "INVALID_VALUE", "Trang hoặc kích thước trang không hợp lệ.");
        }
        NativeSearchQuery query = new NativeSearchQuery("""
            from learning_hub.community_contents c
            left join learning_hub.community_answers a on a.content_id = c.content_id
            join learning_hub.community_contents ac on ac.content_id = coalesce(a.question_content_id, c.content_id)
            left join learning_hub.community_questions question on question.content_id = c.content_id
            left join learning_hub.community_wiki_pages wiki on wiki.content_id = c.content_id
            left join learning_hub.community_radar_entries radar on radar.content_id = c.content_id
            left join learning_hub.community_review_proposals proposal on proposal.content_id = c.content_id
            left join learning_hub.community_knowledge_requests kr on kr.content_id = c.content_id
            join learning_hub.community_content_revisions r
              on r.revision_id = coalesce(c.current_published_revision_id, c.current_draft_revision_id)
            """);
        query.and("""
            c.owner_org_id = :org and ac.owner_org_id = :org
            and c.deleted_at is null and ac.deleted_at is null
            and c.lifecycle_status <> :hidden and ac.lifecycle_status <> :hidden
            and (ac.visibility = :organization
              or (ac.visibility = :guildVisibility and exists (
                  select 1 from learning_hub.community_guild_members m
                  join learning_hub.community_guilds g on g.guild_id = m.guild_id
                  where m.guild_id = ac.primary_guild_id and m.user_id = :user
                    and m.status = :active and g.owner_org_id = :org))
              or (ac.visibility = :restricted and (ac.author_user_id = :user or ac.maintainer_user_id = :user
                or exists (
                  select 1 from learning_hub.community_content_access_grants ag
                  where ag.content_id = ac.content_id and ag.status = :active
                    and (ag.expires_at is null or ag.expires_at > :now)
                    and (ag.user_id = :user or exists (
                      select 1 from learning_hub.community_guild_members gm
                      join learning_hub.community_guilds gg on gg.guild_id = gm.guild_id
                      where gm.guild_id = ag.guild_id and gm.user_id = :user
                        and gm.status = :active and gg.owner_org_id = :org))))))
            """);
        query.bind("org", orgId);
        query.bind("user", userId);
        query.bind("now", Instant.now());
        query.bind("hidden", CommunityCodes.HIDDEN);
        query.bind("organization", CommunityCodes.ORGANIZATION);
        query.bind("guildVisibility", CommunityCodes.GUILD);
        query.bind("restricted", CommunityCodes.RESTRICTED);
        query.bind("active", CommunityCodes.ACTIVE);
        if (publisherOnly) {
            query.and("(c.author_user_id = :user or c.maintainer_user_id = :user) and c.lifecycle_status = :active");
            query.and("""
                coalesce(proposal.review_status,'draft') not in ('concluded','closed')
                  and coalesce(kr.request_status,'open')<>'closed'
                """);
            query.and("""
                coalesce(c.primary_guild_id,wiki.guild_id,radar.guild_id) is null or exists (
                  select 1 from learning_hub.community_guilds governing
                  where governing.guild_id=coalesce(c.primary_guild_id,wiki.guild_id,radar.guild_id)
                    and governing.status='active' and governing.owner_user_id in (select people.user_id from (
                """ + directory.activePeopleSql() + ") people))");
            query.bind("visibleOrg", orgId);
        }
        if (input.mine()) {
            query.and("c.author_user_id = :user");
        } else {
            query.and("c.current_published_revision_id is not null");
        }
        if (input.kind() != null) {
            if (!CommunityCodes.KINDS.contains(input.kind())) {
                throw BusinessException.validation("kind", "INVALID_VALUE", "Loại nội dung không hợp lệ.");
            }
            query.and("c.kind = :kind");
            query.bind("kind", input.kind());
        }
        if (input.guildId() != null) {
            query.and("""
                c.primary_guild_id = :feedGuild or wiki.guild_id = :feedGuild or radar.guild_id = :feedGuild or exists (
                  select 1 from learning_hub.community_content_guilds cg
                  where cg.content_id = c.content_id and cg.guild_id = :feedGuild)
                """);
            query.bind("feedGuild", input.guildId());
        }
        documentFilters(query, input.documentFilter());
        if (input.q() != null && !input.q().isBlank()) {
            if (input.q().length() > CommunityLimits.QUERY) {
                throw BusinessException.validation("q", "TOO_LONG", "Từ khóa tối đa 300 ký tự.");
            }
            query.and("position(lower(:q) in lower(r.title || ' ' || r.body_markdown)) > 0");
            query.bind("q", input.q().trim());
        }
        if (input.questionId() != null) {
            query.and("a.question_content_id = :questionId");
            query.bind("questionId", input.questionId());
        }
        if (input.resolutionStatus() != null) {
            if (!List.of(CommunityCodes.UNANSWERED, CommunityCodes.ANSWERED, CommunityCodes.SOLVED)
                .contains(input.resolutionStatus())) {
                throw BusinessException.validation("resolutionStatus", "INVALID_VALUE", "Trạng thái không hợp lệ.");
            }
            query.and("question.resolution_status = :resolution");
            query.bind("resolution", input.resolutionStatus());
        }
        if (input.publicationState() != null) {
            switch (input.publicationState()) {
                case CommunityCodes.DRAFT -> query.and("c.current_draft_revision_id is not null");
                case CommunityCodes.PUBLISHED -> query.and("c.current_published_revision_id is not null");
                default -> throw BusinessException.validation("publicationState", "INVALID_VALUE",
                    "Trạng thái xuất bản không hợp lệ.");
            }
        }
        if (input.lifecycleStatus() != null) {
            if (!List.of(CommunityCodes.ACTIVE, CommunityCodes.CLOSED, CommunityCodes.ARCHIVED)
                .contains(input.lifecycleStatus())) {
                throw BusinessException.validation("lifecycleStatus", "INVALID_VALUE", "Vòng đời không hợp lệ.");
            }
            query.and("c.lifecycle_status = :lifecycle");
            query.bind("lifecycle", input.lifecycleStatus());
        }
        if (input.tagIds() != null && !input.tagIds().isEmpty()) {
            if (input.tagIds().size() > CommunityLimits.TAGS
                || input.tagIds().stream().anyMatch(id -> id == null || id < 1)) {
                throw BusinessException.validation("tagIds", "INVALID_VALUE", "Danh sách tag không hợp lệ.");
            }
            query.and("""
                exists (select 1 from learning_hub.community_content_tags ct
                  where ct.content_id = ac.content_id and ct.tag_id in (:tags))
                """);
            query.bind("tags", input.tagIds());
        }
        String order = input.sort() == null ? "lastActivityAt" : input.sort();
        Map<String, String> sorts = new HashMap<>(Map.of("lastActivityAt", "c.last_activity_at",
            "createTime", "c.create_time", "publishedAt", "r.published_at", "id", "c.content_id"));
        sorts.put("score", """
            (select coalesce(sum(v.value), 0) from learning_hub.community_votes v
              where v.content_id = c.content_id)
            """.strip());
        Sort sort = Sort.by(Sort.Direction.DESC, order).and(Sort.by(Sort.Direction.DESC, "id"));
        if (input.questionId() != null) {
            sorts.put("accepted", """
                case when c.content_id = (select aq.accepted_answer_content_id
                  from learning_hub.community_questions aq where aq.content_id = a.question_content_id)
                  then 1 else 0 end
                """.strip());
            sort = Sort.by(Sort.Direction.DESC, "accepted", "score")
                .and(Sort.by(Sort.Direction.ASC, "createTime", "id"));
        }
        return query.page(entityManager, "select c.*\n", CommunityContent.class,
            PageRequest.of(input.page(), input.size(), sort), sorts);
    }

    private void documentFilters(NativeSearchQuery query, DocumentFilter filter) {
        if (filter == null) {
            return;
        }
        if (filter.parentPageId() != null) {
            query.and("wiki.parent_page_content_id = :parentPage");
            query.bind("parentPage", filter.parentPageId());
        } else if (filter.rootPages()) {
            query.and("wiki.parent_page_content_id is null");
        }
        if (filter.radarStage() != null) {
            if (!List.of("assess", "trial", "adopt", "hold").contains(filter.radarStage())) {
                throw BusinessException.validation("stage", "INVALID_VALUE", "Radar stage chưa hợp lệ.");
            }
            query.and("r.context_json -> 'radar' ->> 'stage' = :radarStage");
            query.bind("radarStage", filter.radarStage());
        }
        if (filter.dueBefore() != null) {
            query.and("""
                coalesce(wiki.review_due_at, radar.review_due_at, (
                  select m.next_review_due_at from learning_hub.community_maintenance_reviews m
                  where m.content_id = c.content_id order by m.maintenance_review_id desc limit 1)) <= :dueBefore
                or exists (select 1 from learning_hub.community_maintenance_reviews obsolete
                  where obsolete.revision_id = c.current_published_revision_id and obsolete.outcome = :obsolete
                    and obsolete.maintenance_review_id = (select max(latest.maintenance_review_id)
                      from learning_hub.community_maintenance_reviews latest where latest.revision_id = obsolete.revision_id))
                """);
            query.bind("dueBefore", filter.dueBefore());
            query.bind("obsolete", "obsolete");
        }
        if (filter.reviewStatus() != null) {
            if (!List.of("draft", "open", "in_review", "concluded", "closed").contains(filter.reviewStatus())) {
                throw BusinessException.validation("status", "INVALID_VALUE", "Trạng thái review chưa hợp lệ.");
            }
            query.and("proposal.review_status = :reviewStatus");
            query.bind("reviewStatus", filter.reviewStatus());
        }
        if (filter.requestStatus() != null) {
            if (!List.of("open", "claimed", "fulfilled", "closed").contains(filter.requestStatus())) {
                throw BusinessException.validation("status", "INVALID_VALUE", "Trạng thái yêu cầu chưa hợp lệ.");
            }
            query.and(CommunityAudienceSql.EFFECTIVE_REQUEST_STATUS + " = :requestStatus");
            query.bind("requestStatus", filter.requestStatus());
        }
        if (filter.speaker() != null && !filter.speaker().isBlank()) {
            query.and("""
                exists (select 1 from jsonb_array_elements(r.context_json -> 'recording' -> 'speakers') speaker
                  where position(lower(:speaker) in lower(speaker ->> 'displayName')) > 0)
                """);
            query.bind("speaker", filter.speaker());
        }
        if (filter.heldFrom() != null) {
            query.and("cast(r.context_json -> 'recording' ->> 'heldAt' as timestamptz) >= :heldFrom");
            query.bind("heldFrom", filter.heldFrom());
        }
        if (filter.heldTo() != null) {
            query.and("cast(r.context_json -> 'recording' ->> 'heldAt' as timestamptz) <= :heldTo");
            query.bind("heldTo", filter.heldTo());
        }
        if (filter.olderThanDays() != null) {
            query.and("r.published_at <= :olderThan");
            query.bind("olderThan", Instant.now().minusSeconds(filter.olderThanDays() * 86400L));
        }
        if (filter.followingOnly()) {
            query.and("""
                exists (select 1 from learning_hub.community_subscriptions follow
                  where follow.user_id = :user and follow.delivery_mode <> :off
                    and (follow.content_id = c.content_id or follow.author_user_id = c.author_user_id
                      or follow.guild_id = c.primary_guild_id or follow.guild_id = wiki.guild_id
                      or follow.guild_id = radar.guild_id or exists (
                        select 1 from learning_hub.community_content_tags ft
                        where ft.content_id = ac.content_id and ft.tag_id = follow.tag_id)))
                """);
            query.bind("off", "off");
        }
    }

    public List<CommunityContent> byRequest(String actorId, UUID requestId) {
        return repository.list("""
            select c from CommunityContent c
            where c.createdBy = :actor and c.clientRequestId = :request
            """, CommunityContent.class, Map.of("actor", actorId, "request", requestId));
    }

    public List<ContentRevision> revisions(Long contentId) {
        return repository.list("""
            select r from ContentRevision r where r.contentId = :content order by r.revisionNo desc
            """, ContentRevision.class, Map.of("content", contentId));
    }

    public List<ContentRevision> revisionBatch(List<Long> ids) {
        return ids.isEmpty() ? List.of() : repository.list("""
            select r from ContentRevision r where r.revisionId in (:ids)
            """, ContentRevision.class, Map.of("ids", ids));
    }

    public List<ContentRevision> history(Long id, boolean editor, int offset, int size) {
        return repository.page("""
            select r from ContentRevision r where r.contentId = :content and r.deletedAt is null
              and (:editor = true or r.revisionStatus in (:published, :superseded))
            order by r.revisionNo desc, r.revisionId desc
            """, ContentRevision.class, Map.of("content", id, "editor", editor,
                "published", CommunityCodes.PUBLISHED, "superseded", CommunityCodes.SUPERSEDED), offset, size);
    }

    public long historyCount(Long id, boolean editor) {
        return repository.one("""
            select count(r) from ContentRevision r where r.contentId = :content and r.deletedAt is null
              and (:editor = true or r.revisionStatus in (:published, :superseded))
            """, Long.class, Map.of("content", id, "editor", editor,
                "published", CommunityCodes.PUBLISHED, "superseded", CommunityCodes.SUPERSEDED));
    }
}
