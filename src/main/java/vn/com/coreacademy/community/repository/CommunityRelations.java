package vn.com.coreacademy.community.repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.entity.Answer;
import vn.com.coreacademy.community.entity.CommunityComment;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.CommunityTag;
import vn.com.coreacademy.community.entity.CommunityVote;
import vn.com.coreacademy.community.entity.ContentAccessGrant;
import vn.com.coreacademy.community.entity.ContentTag;
import vn.com.coreacademy.community.entity.RevisionAsset;
import vn.com.coreacademy.community.entity.TagAlias;

@Repository
@RequiredArgsConstructor
public class CommunityRelations {
    private final CommunityRepository repository;

    public List<ContentTag> contentTags(Long contentId) {
        return repository.list("""
            select t from ContentTag t where t.contentId = :content order by t.contentTagId
            """, ContentTag.class, Map.of("content", contentId));
    }

    public List<CommunityTag> tags(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : repository.list("""
            select t from CommunityTag t where t.tagId in (:ids) order by t.name, t.tagId
            """, CommunityTag.class, Map.of("ids", ids));
    }

    public List<TagAlias> aliases(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : repository.list("""
            select a from TagAlias a where a.tagId in (:ids) order by a.normalizedAlias, a.aliasId
            """, TagAlias.class, Map.of("ids", ids));
    }

    public List<ContentAccessGrant> grants(Long contentId) {
        return repository.list("""
            select g from ContentAccessGrant g where g.contentId = :content and g.status = :active
            order by g.grantId
            """, ContentAccessGrant.class, Map.of("content", contentId, "active", CommunityCodes.ACTIVE));
    }

    public List<RevisionAsset> assets(Long revisionId) {
        return repository.list("""
            select a from RevisionAsset a where a.revisionId = :revision order by a.sortNo, a.revisionAssetId
            """, RevisionAsset.class, Map.of("revision", revisionId));
    }

    public long otherAnswers(Long questionId, String actorId) {
        return repository.one("""
            select count(a) from Answer a join CommunityContent c on c.contentId = a.contentId
            where a.questionContentId = :question and c.currentPublishedRevisionId is not null
              and c.deletedAt is null and c.lifecycleStatus <> :hidden and c.authorUserId <> :actor
            """, Long.class, Map.of("question", questionId, "hidden", CommunityCodes.HIDDEN, "actor", actorId));
    }

    public long visibleAnswerCount(Long questionId) {
        return repository.one("""
            select count(a) from Answer a join CommunityContent c on c.contentId = a.contentId
            where a.questionContentId = :question and c.currentPublishedRevisionId is not null
              and c.deletedAt is null and c.lifecycleStatus <> :hidden
            """, Long.class, Map.of("question", questionId, "hidden", CommunityCodes.HIDDEN));
    }

    public List<CommunityVote> ownVotes(Long contentId, String userId) {
        return repository.list("""
            select v from CommunityVote v where v.contentId = :content and v.userId = :user
            """, CommunityVote.class, Map.of("content", contentId, "user", userId));
    }

    public Object[] voteTotals(Long contentId) {
        return repository.one("""
            select coalesce(sum(v.value), 0),
              coalesce(sum(case when v.value = 1 then 1 else 0 end), 0),
              coalesce(sum(case when v.value = -1 then 1 else 0 end), 0)
            from CommunityVote v where v.contentId = :content
            """, Object[].class, Map.of("content", contentId));
    }

    public List<CommunityComment> commentRequest(String actorId, UUID requestId) {
        return repository.list("""
            select c from CommunityComment c where c.createdBy = :actor and c.clientRequestId = :request
            """, CommunityComment.class, Map.of("actor", actorId, "request", requestId));
    }

    public List<CommunityComment> comments(Long contentId, int offset, int limit) {
        return repository.page("""
            select c from CommunityComment c where c.contentId = :content
            order by c.createTime, c.commentId
            """, CommunityComment.class, Map.of("content", contentId), offset, limit);
    }

    public long commentCount(Long contentId) {
        return repository.one("""
            select count(c) from CommunityComment c where c.contentId = :content
            """, Long.class, Map.of("content", contentId));
    }

    public List<CommunityComment> comments(Long contentId, Long threadId, int offset, int limit) {
        if (threadId == null) {
            return comments(contentId, offset, limit);
        }
        return repository.page("""
            select c from CommunityComment c where c.contentId=:content and c.reviewThreadId=:thread
            order by c.createTime,c.commentId
            """, CommunityComment.class, Map.of("content", contentId, "thread", threadId), offset, limit);
    }

    public long commentCount(Long contentId, Long threadId) {
        return threadId == null ? commentCount(contentId) : repository.one("""
            select count(c) from CommunityComment c where c.contentId=:content and c.reviewThreadId=:thread
            """, Long.class, Map.of("content", contentId, "thread", threadId));
    }

    public List<CommunityTag> tagPage(Long orgId, String q, int offset, int limit) {
        return repository.page("""
            select t from CommunityTag t where t.ownerOrgId = :org
              and (locate(lower(:q), lower(t.name)) > 0 or locate(lower(:q), lower(t.slug)) > 0)
            order by t.name, t.slug, t.tagId
            """, CommunityTag.class, Map.of("org", orgId, "q", q), offset, limit);
    }

    public long tagCount(Long orgId, String q) {
        return repository.one("""
            select count(t) from CommunityTag t where t.ownerOrgId = :org
              and (locate(lower(:q), lower(t.name)) > 0 or locate(lower(:q), lower(t.slug)) > 0)
            """, Long.class, Map.of("org", orgId, "q", q));
    }

    public boolean tagNameUsed(Long orgId, String alias, Long exceptId) {
        return repository.one("""
            select count(t) from CommunityTag t where t.ownerOrgId = :org and t.tagId <> :except and t.status <> 'merged'
              and (t.slug = :alias or exists (select a.aliasId from TagAlias a
                where a.tagId = t.tagId and a.normalizedAlias = :alias))
            """, Long.class, Map.of("org", orgId, "except", exceptId == null ? -1L : exceptId,
                "alias", alias)) > 0;
    }

    public CommunityOutboxEvent latestLifecycle(Long contentId) {
        var events = repository.page("""
            select e from CommunityOutboxEvent e where e.contentId = :content and e.eventType = :type
            order by e.eventId desc
            """, CommunityOutboxEvent.class, Map.of("content", contentId, "type", "content_lifecycle"), 0, 1);
        return events.isEmpty() ? null : events.get(0);
    }

    /** Sự kiện gần nhất có thể đóng câu hỏi: tác giả đóng, đánh dấu trùng lặp hoặc moderator xử lý. */
    /**
     * Các sự kiện có thể đã đóng câu hỏi, mới nhất trước. Người gọi chọn sự kiện đóng thật sự theo payload,
     * vì mở lưu trữ, bỏ trùng lặp hay cảnh báo cũng sinh sự kiện cùng loại sau lần đóng.
     */
    public List<CommunityOutboxEvent> closingCandidates(Long contentId) {
        return repository.page("""
            select e from CommunityOutboxEvent e where e.contentId = :content and e.eventType in :types
            order by e.eventId desc
            """, CommunityOutboxEvent.class, Map.of("content", contentId, "types",
                List.of("content_lifecycle", "question_duplicate_changed", "content_moderated")), 0, 20);
    }

    public boolean moderatorClosed(Long contentId) {
        return repository.one("""
            select count(a) from ModerationAction a join CommunityReport r on r.reportId = a.reportId
            where r.contentId = :content and a.actionType = :action
            """, Long.class, Map.of("content", contentId, "action", "close")) > 0;
    }
}
