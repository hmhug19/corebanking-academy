package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityLimits;
import vn.com.coreacademy.community.dto.ContentRequests.CreateComment;
import vn.com.coreacademy.community.dto.ContentRequests.UpdateComment;
import vn.com.coreacademy.community.dto.ContentViews.CommentView;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.entity.CommunityComment;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.ReviewThread;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.mapper.CommunityMapper;
import vn.com.coreacademy.community.repository.CommunityRelations;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityCommentService {
    private final CommunityRepository repository;
    private final CommunityRelations relations;
    private final CommunityPolicy policy;
    private final CommunityContentService contents;
    private final CommunityMapper mapper;
    private final CommunityPayload payload;
    private final IdentityWriteLock writeLock;
    private final LearningIdentityService identity;

    public PageResponse<CommentView> list(Long contentId, int page, int size) {
        return list(contentId, null, page, size);
    }

    public PageResponse<CommentView> list(Long contentId, Long threadId, int page, int size) {
        var actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        contents.requirePublished(content);
        CommunityPages.check(page, size);
        if (threadId != null && !Objects.equals(repository.require(ReviewThread.class, threadId).getProposalContentId(),
            contentId)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        return CommunityPages.of(relations.comments(contentId, threadId, page * size, size).stream().map(this::safeView)
            .toList(),
            page, size, relations.commentCount(contentId, threadId));
    }

    @Transactional
    public CommentView create(Long contentId, CreateComment request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        contents.requirePublished(content);
        String hash = payload.hash("comment:" + contentId, request);
        var previous = relations.commentRequest(actor.person().id(), request.clientRequestId());
        if (!previous.isEmpty()) {
            CommunityComment comment = previous.get(0);
            if (!Objects.equals(comment.getContentId(), contentId) || !hash.equals(comment.getRequestHash())) {
                throw new BusinessException(CommunityResponseCode.IDEMPOTENCY_CONFLICT);
            }
            return safeView(comment);
        }
        policy.requireInteractive(content, actor);
        payload.markdown(request.bodyMarkdown());
        contents.validateMentions(content, request.bodyMarkdown());
        Long revisionId = request.reviewThreadId() == null ? null : validateThread(contentId, request.reviewThreadId());
        validateParent(contentId, request.parentCommentId(), request.reviewThreadId(), revisionId);
        CommunityComment comment = new CommunityComment();
        comment.setContentId(contentId);
        comment.setAuthorUserId(actor.person().id());
        comment.setParentCommentId(request.parentCommentId());
        comment.setReviewThreadId(request.reviewThreadId());
        comment.setRevisionId(revisionId);
        comment.setBodyMarkdown(request.bodyMarkdown());
        contents.validateMentions(content, request.bodyMarkdown());
        comment.setStatus("visible");
        comment.setClientRequestId(request.clientRequestId());
        comment.setRequestHash(hash);
        repository.save(comment, actor.person().id());
        contents.event(content, "comment_created", actor.person().id(), JsonNodeFactory.instance.objectNode()
            .put("commentId", comment.getCommentId()));
        repository.flush();
        return safeView(comment);
    }

    @Transactional
    public CommentView update(Long id, UpdateComment request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityComment comment = repository.require(CommunityComment.class, id);
        CommunityContent content = policy.readable(comment.getContentId(), actor);
        policy.requireInteractive(content, actor);
        if (!Objects.equals(comment.getAuthorUserId(), actor.person().id())) {
            throw new AccessDeniedException("Comment author required");
        }
        if (comment.getDeletedAt() != null || !"visible".equals(comment.getStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        policy.version(comment, request.expectedVersion());
        payload.markdown(request.bodyMarkdown());
        contents.validateMentions(content, request.bodyMarkdown());
        comment.setBodyMarkdown(request.bodyMarkdown());
        repository.save(comment, actor.person().id());
        contents.event(content, "comment_updated", actor.person().id(), JsonNodeFactory.instance.objectNode()
            .put("commentId", comment.getCommentId()));
        repository.flush();
        return safeView(comment);
    }

    @Transactional
    public void delete(Long id, Long expectedVersion) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityComment comment = repository.require(CommunityComment.class, id);
        CommunityContent content = policy.readable(comment.getContentId(), actor);
        policy.requireInteractive(content, actor);
        if (!Objects.equals(comment.getAuthorUserId(), actor.person().id())) {
            throw new AccessDeniedException("Comment author required");
        }
        policy.version(comment, expectedVersion);
        if (comment.getDeletedAt() != null || !"visible".equals(comment.getStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        comment.setDeletedAt(Instant.now());
        comment.setDeletedBy(actor.person().id());
        repository.save(comment, actor.person().id());
        contents.event(content, "comment_deleted", actor.person().id());
    }

    private Long validateThread(Long contentId, Long threadId) {
        ReviewThread thread = repository.require(ReviewThread.class, threadId);
        if (!Objects.equals(thread.getProposalContentId(), contentId) || !"open".equals(thread.getStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        return thread.getRevisionId();
    }

    private void validateParent(Long contentId, Long parentId, Long threadId, Long revisionId) {
        int depth = 1;
        while (parentId != null) {
            CommunityComment parent = repository.require(CommunityComment.class, parentId);
            if (++depth > CommunityLimits.COMMENT_DEPTH || !Objects.equals(parent.getContentId(), contentId)
                || !Objects.equals(parent.getReviewThreadId(), threadId)
                || !Objects.equals(parent.getRevisionId(), revisionId)
                || parent.getDeletedAt() != null || !"visible".equals(parent.getStatus())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            parentId = parent.getParentCommentId();
        }
    }

    public List<CommentView> views(List<CommunityComment> values) {
        var actor = policy.actor();
        values.stream().map(CommunityComment::getContentId).distinct().forEach(id -> policy.readable(id, actor));
        var names = identity.fullNames(values.stream().map(CommunityComment::getAuthorUserId)
            .filter(Objects::nonNull).collect(Collectors.toSet()));
        return values.stream().map(value -> safeView(value, actor, names)).toList();
    }

    public CommentView view(CommunityComment comment) {
        policy.readable(comment.getContentId(), policy.actor());
        return safeView(comment);
    }

    private CommentView safeView(CommunityComment comment) {
        var names = comment.getAuthorUserId() == null ? Map.<String, String>of()
            : identity.fullNames(List.of(comment.getAuthorUserId()));
        return safeView(comment, policy.actor(), names);
    }

    private CommentView safeView(CommunityComment comment, Actor actor, Map<String, String> names) {
        boolean hidden = comment.getDeletedAt() != null || !"visible".equals(comment.getStatus());
        boolean writable = !hidden && Objects.equals(comment.getAuthorUserId(), actor.person().id());
        if (writable) {
            try {
                policy.requireInteractive(repository.require(CommunityContent.class, comment.getContentId()), actor);
                writable = comment.getReviewThreadId() == null || "open".equals(repository.require(ReviewThread.class,
                    comment.getReviewThreadId()).getStatus());
            } catch (BusinessException exception) {
                writable = false;
            }
        }
        String user = hidden ? null : comment.getAuthorUserId();
        var author = user == null ? null : new PersonRef(user, names.get(user));
        return new CommentView(comment.getCommentId(), comment.getContentId(), user, comment.getParentCommentId(),
            comment.getReviewThreadId(), hidden ? null : comment.getBodyMarkdown(), hidden
                ? comment.getDeletedAt() == null ? "hidden" : "deleted" : comment.getStatus(),
            comment.getCreateTime(), comment.getRowVersion(), author, comment.getRevisionId(),
                comment.getDeletedAt() != null,
            Map.of("canEdit", writable, "canDelete", writable), comment.getUpdateTime());
    }
}
