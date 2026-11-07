package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.entity.CommunityComment;
import vn.com.coreacademy.community.entity.ReviewInvitation;
import vn.com.coreacademy.community.entity.ReviewThread;

@Repository
@RequiredArgsConstructor
public class CommunityReviewRepository {
    private final CommunityRepository repository;

    public List<ReviewInvitation> invitations(Long contentId, int offset, int size) {
        return repository.page("""
            select i from ReviewInvitation i where i.proposalContentId = :content order by i.invitationId desc
            """, ReviewInvitation.class, Map.of("content", contentId), offset, size);
    }

    public List<ReviewThread> threads(Long contentId, Long revisionId, String status, int offset, int size) {
        var parameters = threadParameters(contentId, revisionId, status);
        return repository.page("""
            select t from ReviewThread t where t.proposalContentId = :content and t.revisionId = :revision
              and (:status is null or t.status = :status) order by t.threadId desc
            """, ReviewThread.class, parameters, offset, size);
    }

    public long threadCount(Long contentId, Long revisionId, String status) {
        if (revisionId == null) {
            return 0;
        }
        return repository.one("""
            select count(t) from ReviewThread t where t.proposalContentId = :content and t.revisionId = :revision
              and (:status is null or t.status = :status)
            """, Long.class, threadParameters(contentId, revisionId, status));
    }

    private Map<String, Object> threadParameters(Long contentId, Long revisionId, String status) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("content", contentId);
        parameters.put("revision", revisionId);
        parameters.put("status", status);
        return parameters;
    }

    public List<ReviewInvitation> invitation(Long contentId, String userId) {
        return repository.list("""
            select i from ReviewInvitation i where i.proposalContentId = :content and i.reviewerUserId = :user
            order by i.invitationId
            """, ReviewInvitation.class, Map.of("content", contentId, "user", userId));
    }

    public boolean accepted(Long contentId, String userId) {
        return invitation(contentId, userId).stream().anyMatch(value -> "accepted".equals(value.getStatus())
            && (value.getExpiresAt() == null || value.getExpiresAt().isAfter(Instant.now())));
    }

    public List<String> guildUsers(List<Long> guildIds) {
        if (guildIds.isEmpty()) {
            return List.of();
        }
        // 501 phát hiện vượt giới hạn; không tải toàn bộ hội nhóm để lọc/phân trang trong bộ nhớ.
        return repository.page("""
            select distinct m.userId from GuildMember m where m.guildId in :guilds and m.status = :status
            order by m.userId
            """, String.class, Map.of("guilds", guildIds, "status", "active"), 0, 501);
    }

    public List<ReviewThread> threadRequest(String actorId, UUID requestId) {
        return repository.list("""
            select t from ReviewThread t where t.createdBy = :actor and t.clientRequestId = :request
            """, ReviewThread.class, Map.of("actor", actorId, "request", requestId));
    }

    public List<CommunityComment> rootComments(List<Long> threadIds) {
        if (threadIds.isEmpty()) {
            return List.of();
        }
        return repository.list("""
            select c from CommunityComment c where c.reviewThreadId in :threads and c.parentCommentId is null
              and c.commentId = (select min(root.commentId) from CommunityComment root
                  where root.reviewThreadId = c.reviewThreadId and root.parentCommentId is null)
            order by c.commentId
            """, CommunityComment.class, Map.of("threads", threadIds));
    }

    public CommunityComment rootComment(Long threadId) {
        var values = repository.page("""
            select c from CommunityComment c where c.reviewThreadId = :thread and c.parentCommentId is null
            order by c.commentId
            """, CommunityComment.class, Map.of("thread", threadId), 0, 1);
        return values.isEmpty() ? null : values.get(0);
    }

    public long openThreads(Long contentId, Long revisionId) {
        if (revisionId == null) {
            return 0;
        }
        return repository.one("""
            select count(t) from ReviewThread t where t.proposalContentId = :content
              and t.revisionId = :revision and t.status = :status
            """, Long.class, Map.of("content", contentId, "revision", revisionId, "status", "open"));
    }

    public long invitationCount(Long contentId) {
        return repository.one("select count(i) from ReviewInvitation i where i.proposalContentId = :content",
            Long.class, Map.of("content", contentId));
    }
}
