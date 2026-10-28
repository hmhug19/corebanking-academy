package vn.com.coreacademy.community.repository;

import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.MaintenanceReview;
import vn.com.coreacademy.community.entity.RevisionApproval;
import vn.com.coreacademy.community.entity.WikiPage;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityDocumentRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;

    public boolean slugUsed(Long guildId, String slug, Long except) {
        return repository.one("""
            select count(w) from WikiPage w where w.guildId = :guild and w.slug = :slug and w.contentId <> :except
            """, Long.class, Map.of("guild", guildId, "slug", slug, "except", except == null ? -1L : except)) > 0;
    }

    public boolean parentCycle(Long contentId, Long parentId) {
        return ((Number) entityManager.createNativeQuery("""
            with recursive tree(id) as (
              select cast(:parent as bigint)
              union
              select w.parent_page_content_id from learning_hub.community_wiki_pages w
              join tree p on p.id = w.content_id where w.parent_page_content_id is not null)
            select count(*) from tree where id = :content
            """).setParameter("parent", parentId).setParameter("content", contentId).getSingleResult()).longValue() > 0;
    }

    public Page<RevisionApproval> approvals(Long revision, int page, int size) {
        var query = new NativeSearchQuery("from learning_hub.community_revision_approvals a\n");
        query.and("a.revision_id = :revision");
        query.bind("revision", revision);
        return query.page(entityManager, "select a.*\n", RevisionApproval.class,
            PageRequest.of(page, size, Sort.by("id")), Map.of("id", "a.approval_id"));
    }

    public Page<MaintenanceReview> maintenance(Long content, int page, int size) {
        var query = new NativeSearchQuery("from learning_hub.community_maintenance_reviews m\n");
        query.and("m.content_id = :content");
        query.bind("content", content);
        return query.page(entityManager, "select m.*\n", MaintenanceReview.class,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")), Map.of("id", "m.maintenance_review_id"));
    }

    public Page<ContentRevision> queue(Actor actor, String kind, Long guildId, int page, int size) {
        var query = new NativeSearchQuery("""
            from learning_hub.community_content_revisions r
            join learning_hub.community_contents c on c.content_id = r.content_id
            left join learning_hub.community_wiki_pages w on w.content_id = c.content_id
            left join learning_hub.community_radar_entries radar on radar.content_id = c.content_id
            """);
        query.and("""
            c.owner_org_id = :org and c.kind in ('wiki', 'radar') and c.lifecycle_status = :active
            and c.deleted_at is null and r.revision_status = :submitted
            and c.current_draft_revision_id = r.revision_id
            """);
        query.bind("org", actor.person().orgId());
        query.bind("active", "active");
        query.bind("submitted", "submitted");
        if (kind != null) {
            query.and("c.kind = :kind");
            query.bind("kind", kind);
        }
        if (guildId != null) {
            query.and("coalesce(w.guild_id, radar.guild_id) = :guild");
            query.bind("guild", guildId);
        }
        return query.page(entityManager, "select r.*\n", ContentRevision.class,
            PageRequest.of(page, size, Sort.by("id")), Map.of("id", "r.revision_id"));
    }

    public boolean obsolete(Long revisionId) {
        var rows = repository.page("""
            select m from MaintenanceReview m where m.revisionId = :revision
            order by m.maintenanceReviewId desc
            """, MaintenanceReview.class, Map.of("revision", revisionId), 0, 1);
        return !rows.isEmpty() && "obsolete".equals(rows.get(0).getOutcome());
    }
}
