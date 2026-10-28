package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.List;
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

    public CommunityComment rootComment(Long threadId) {
        var values = repository.page("""
            select c from CommunityComment c where c.reviewThreadId = :thread and c.parentCommentId is null
            order by c.commentId
            """, CommunityComment.class, Map.of("thread", threadId), 0, 1);
        return values.isEmpty() ? null : values.get(0);
    }

    public long openThreads(Long contentId, Long revisionId) {
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
