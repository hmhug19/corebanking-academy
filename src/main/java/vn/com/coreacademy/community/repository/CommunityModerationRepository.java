package vn.com.coreacademy.community.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.entity.CommunityReport;
import vn.com.coreacademy.community.entity.ModerationAction;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityModerationRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;

    public List<CommunityReport> duplicate(String reporter, Long contentId, Long commentId, String reason) {
        return repository.list("""
            select r from CommunityReport r where r.reporterUserId = :reporter and r.reasonCode = :reason
              and r.reportStatus in ('open', 'in_review')
              and ((:content is not null and r.contentId = :content)
                or (:comment is not null and r.commentId = :comment))
            order by r.reportId
            """, CommunityReport.class, parameters(reporter, contentId, commentId, reason));
    }

    private Map<String, Object> parameters(String reporter, Long contentId, Long commentId, String reason) {
        Map<String, Object> values = new HashMap<>();
        values.put("reporter", reporter);
        values.put("reason", reason);
        values.put("content", contentId);
        values.put("comment", commentId);
        return values;
    }

    public Page<CommunityReport> list(Actor actor, String status, boolean assignedToMe, int page, int size) {
        var query = new NativeSearchQuery("""
            from learning_hub.community_reports r
            left join learning_hub.community_comments comment on comment.comment_id = r.comment_id
            join learning_hub.community_contents c on c.content_id = coalesce(r.content_id, comment.content_id)
            """);
        query.and("c.owner_org_id = :org");
        query.bind("org", actor.person().orgId());
        if (status != null) {
            query.and("r.report_status = :status");
            query.bind("status", status);
        }
        if (assignedToMe) {
            query.and("r.assigned_moderator_user_id = :actor");
            query.bind("actor", actor.person().id());
        }
        return query.page(entityManager, "select r.*\n", CommunityReport.class,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")), Map.of("id", "r.report_id"));
    }

    public List<ModerationAction> actions(Long reportId) {
        return repository.list("""
            select a from ModerationAction a where a.reportId = :report order by a.actionId
            """, ModerationAction.class, Map.of("report", reportId));
    }

    public ModerationAction latestTargetAction(CommunityReport report) {
        var values = new HashMap<String, Object>();
        values.put("content", report.getContentId());
        values.put("comment", report.getCommentId());
        var actions = repository.page("""
            select a from ModerationAction a join CommunityReport r on r.reportId = a.reportId
            where (:content is not null and r.contentId = :content)
              or (:comment is not null and r.commentId = :comment)
            order by a.actionId desc
            """, ModerationAction.class, values, 0, 1);
        return actions.isEmpty() ? null : actions.get(0);
    }
}
