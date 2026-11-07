package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.ContentRequests.CreateComment;
import vn.com.coreacademy.community.dto.ContentViews.CommentView;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateReview;
import vn.com.coreacademy.community.dto.DocumentRequests.FollowUpReview;
import vn.com.coreacademy.community.dto.ReviewDtos.Close;
import vn.com.coreacademy.community.dto.ReviewDtos.Conclude;
import vn.com.coreacademy.community.dto.ReviewDtos.CreateThread;
import vn.com.coreacademy.community.dto.ReviewDtos.InvitationView;
import vn.com.coreacademy.community.dto.ReviewDtos.Invite;
import vn.com.coreacademy.community.dto.ReviewDtos.Respond;
import vn.com.coreacademy.community.dto.ReviewDtos.ReviewCard;
import vn.com.coreacademy.community.dto.ReviewDtos.ReviewView;
import vn.com.coreacademy.community.dto.ReviewDtos.ThreadAction;
import vn.com.coreacademy.community.dto.ReviewDtos.ThreadView;
import vn.com.coreacademy.community.entity.CommunityAsset;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.ContentLink;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.ReviewInvitation;
import vn.com.coreacademy.community.entity.ReviewProposal;
import vn.com.coreacademy.community.entity.ReviewThread;
import vn.com.coreacademy.community.entity.RevisionAsset;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.CommunityReviewRepository;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.community.repository.ContentRepository.DocumentFilter;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityReviewService {
    private final CommunityRepository repository;
    private final CommunityReviewRepository reviews;
    private final CommunityContentService contents;
    private final CommunityPolicy policy;
    private final CommunityGuildService guilds;
    private final CommunityCommentService comments;
    private final CommunityPayload payload;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;

    public PageResponse<ReviewCard> list(String q, Long guildId, String status, int page, int size) {
        if (status != null && !Set.of("draft", "open", "concluded", "closed").contains(status)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        return CommunityPages.map(contents.list(new ContentRepository.Search("architecture_review", q, null, null,
            null, false, null, null, page, size, "publishedAt", guildId,
            new DocumentFilter(null, false, null, null, status, null, null, null, null, null, false))), card -> {
                var review = repository.require(ReviewProposal.class, card.contentId());
                return new ReviewCard(card, review.getReviewStatus(), review.getDueAt(), card.publishedRevisionId(),
                    reviews.openThreads(card.contentId(), card.publishedRevisionId()),
                    reviews.invitationCount(card.contentId()));
            });
    }

    public ReviewView detail(Long contentId) {
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        contents.requireKind(content, "architecture_review");
        return view(content, repository.require(ReviewProposal.class, contentId), actor);
    }

    public PageResponse<ThreadView> threads(Long contentId, Long revisionId, String status, int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        contents.requireKind(content, "architecture_review");
        // Also prevents a draft-only proposal being exposed to other organization members.
        contents.detail(content, actor, false);
        publishedRevision(content, revisionId, actor);
        if (status != null && !Set.of("open", "resolved").contains(status)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        var rows = reviews.threads(contentId, revisionId, status, page * size, size);
        var roots = comments.views(reviews.rootComments(rows.stream().map(ReviewThread::getThreadId).toList()));
        Map<Long, CommentView> rootByThread = new HashMap<>();
        roots.forEach(root -> rootByThread.putIfAbsent(root.reviewThreadId(), root));
        var names = identity.fullNames(rows.stream().map(ReviewThread::getResolvedBy)
            .filter(Objects::nonNull).collect(Collectors.toSet()));
        boolean writable = reviewActions(content, repository.require(ReviewProposal.class, contentId), actor)
            .contains("comment");
        boolean reviewer = policy.isAuthorOrMaintainer(content, actor) || reviews.accepted(contentId, actor.person().id());
        return CommunityPages.of(rows.stream().map(thread -> threadView(thread, actor, writable, reviewer,
            rootByThread.get(thread.getThreadId()), names)).toList(), page, size,
            reviews.threadCount(contentId, revisionId, status));
    }

    public PageResponse<InvitationView> invitations(Long contentId, int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        contents.requireKind(content, "architecture_review");
        contents.detail(content, actor, false);
        var rows = reviews.invitations(contentId, page * size, size);
        var names = identity.fullNames(rows.stream().flatMap(row -> Stream.of(row.getReviewerUserId(), row.getInvitedBy()))
            .collect(Collectors.toSet()));
        boolean writable = reviewActions(content, repository.require(ReviewProposal.class, contentId), actor)
            .contains("comment");
        return CommunityPages.of(rows.stream().map(value -> invitationView(value, actor, content, writable, names))
            .toList(), page, size, reviews.invitationCount(contentId));
    }

    @Transactional
    public ContentDetail create(CreateReview request) {
        writeLock.acquire();
        var actor = policy.actor();
        validDue(request.dueAt());
        var content = contents.create("architecture_review", null, request.content(request.primaryGuildId()),
            payload.hash("architecture_review", request));
        createProposal(content.contentId(), request.dueAt(), actor);
        return contents.detail(content.contentId(), true);
    }

    @Transactional
    public ContentDetail followUp(Long contentId, FollowUpReview request) {
        writeLock.acquire();
        var actor = policy.actor();
        var source = policy.readable(contentId, actor);
        contents.requireKind(source, "architecture_review");
        policy.requireAuthor(source, actor);
        var proposal = repository.require(ReviewProposal.class, contentId);
        if (!Set.of("concluded", "closed").contains(proposal.getReviewStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        publishedRevision(source, request.sourceRevisionId(), actor);
        validDue(request.dueAt());
        var result = contents.create("architecture_review", null, request.content(request.primaryGuildId()),
            payload.hash("review_follow_up:" + contentId, request));
        if (repository.find(ReviewProposal.class, result.contentId()) == null) {
            createProposal(result.contentId(), request.dueAt(), actor);
            ContentLink link = new ContentLink();
            link.setSourceContentId(result.contentId());
            link.setTargetContentId(contentId);
            link.setRelationType("follow_up");
            repository.save(link, actor.person().id());
            // Revision nguồn được chụp trong sự kiện bất biến, không lấy lại pointer có thể đổi sau này.
            contents.event(repository.require(CommunityContent.class, result.contentId()), "review_follow_up",
                actor.person().id(), JsonNodeFactory.instance.objectNode().put("sourceRevisionId",
                    request.sourceRevisionId()).put("sourceContentId", contentId));
        }
        return contents.detail(result.contentId(), true);
    }

    /** Chụp tập người nhận hiện tại; lời mời không phải quyền truy cập vào bản thiết kế. */
    @Transactional
    public List<InvitationView> invite(Long contentId, Invite request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = open(contentId, actor);
        owner(content, actor);
        validDue(request.expiresAt());
        var users = request.reviewerUserIds() == null ? List.<String>of() : request.reviewerUserIds();
        var groupIds = request.guildIds() == null ? List.<Long>of() : request.guildIds();
        if (new HashSet<>(users).size() != users.size() || new HashSet<>(groupIds).size() != groupIds.size()) {
            throw new BusinessException(CommunityResponseCode.DUPLICATE);
        }
        groupIds.forEach(id -> guilds.readable(id, actor));
        Set<String> recipients = new LinkedHashSet<>(users);
        recipients.addAll(reviews.guildUsers(groupIds));
        if (recipients.isEmpty() || recipients.size() > 500) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        // Kiểm tra tất cả trước khi ghi để một ID sai không tạo lời mời dở dang.
        for (String userId : recipients) {
            var recipient = identity.actor(userId);
            if (!Objects.equals(recipient.person().orgId(), content.getOwnerOrgId())
                || !policy.canRead(content, recipient) || policy.isAuthorOrMaintainer(content, recipient)) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        }
        List<InvitationView> result = new ArrayList<>();
        var eventData = JsonNodeFactory.instance.objectNode();
        var newlyInvited = eventData.putArray("recipientUserIds");
        for (String userId : recipients) {
            var previous = reviews.invitation(contentId, userId);
            ReviewInvitation invitation;
            if (!previous.isEmpty()) {
                invitation = previous.get(0);
            } else {
                invitation = new ReviewInvitation();
                invitation.setProposalContentId(contentId);
                invitation.setReviewerUserId(userId);
                invitation.setInvitedBy(actor.person().id());
                invitation.setStatus("pending");
                invitation.setExpiresAt(request.expiresAt());
                repository.save(invitation, actor.person().id());
                newlyInvited.add(userId);
            }
            repository.flush();
            result.add(invitationView(invitation, actor));
        }
        if (!newlyInvited.isEmpty()) {
            contents.event(content, "review_invited", actor.person().id(), eventData);
        }
        return result;
    }

    @Transactional
    public InvitationView respond(Long invitationId, Respond request) {
        writeLock.acquire();
        var actor = policy.actor();
        var value = repository.require(ReviewInvitation.class, invitationId);
        var content = open(value.getProposalContentId(), actor);
        policy.version(value, request.expectedVersion());
        if (value.getExpiresAt() != null && !value.getExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        boolean reviewer = Objects.equals(value.getReviewerUserId(), actor.person().id());
        switch (request.action()) {
            case "accept", "decline" -> {
                if (!reviewer || !"pending".equals(value.getStatus())) {
                    throw new AccessDeniedException("Pending review recipient required");
                }
                value.setStatus("accept".equals(request.action()) ? "accepted" : "declined");
            }
            case "complete" -> {
                if (!reviewer || !"accepted".equals(value.getStatus())) {
                    throw new AccessDeniedException("Accepted reviewer required");
                }
                value.setStatus("completed");
            }
            case "cancel" -> {
                owner(content, actor);
                if (!Set.of("pending", "accepted").contains(value.getStatus())) {
                    throw new BusinessException(CommunityResponseCode.INVALID_STATE);
                }
                value.setStatus("cancelled");
            }
            default -> throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        value.setRespondedAt(Instant.now());
        repository.save(value, actor.person().id());
        repository.flush();
        return invitationView(value, actor);
    }

    /** Thread và bình luận gốc được ghi trong cùng transaction, giữ nguyên revision khi xuất bản bản mới. */
    @Transactional
    public ThreadView thread(Long contentId, CreateThread request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        contents.requireKind(content, "architecture_review");
        String hash = payload.hash("review_thread:" + contentId, request);
        var previous = reviews.threadRequest(actor.person().id(), request.clientRequestId());
        if (!previous.isEmpty()) {
            var value = previous.get(0);
            if (!Objects.equals(value.getProposalContentId(), contentId) || !hash.equals(value.getRequestHash())) {
                throw new BusinessException(CommunityResponseCode.IDEMPOTENCY_CONFLICT);
            }
            return threadView(value, actor);
        }
        open(contentId, actor);
        publishedRevision(content, request.revisionId(), actor);
        validateAnchor(request);
        payload.markdown(request.bodyMarkdown());
        ReviewThread thread = new ReviewThread();
        thread.setProposalContentId(contentId);
        thread.setRevisionId(request.revisionId());
        thread.setRevisionAssetId(request.revisionAssetId());
        thread.setAnchorJson(request.anchor());
        thread.setStatus("open");
        thread.setClientRequestId(request.clientRequestId());
        thread.setRequestHash(hash);
        repository.save(thread, actor.person().id());
        repository.flush();
        comments.create(contentId, new CreateComment(request.clientRequestId(), null, thread.getThreadId(),
            request.bodyMarkdown()));
        return threadView(thread, actor);
    }

    @Transactional
    public ThreadView threadAction(Long threadId, ThreadAction request) {
        writeLock.acquire();
        var actor = policy.actor();
        var thread = repository.require(ReviewThread.class, threadId);
        var content = open(thread.getProposalContentId(), actor);
        policy.version(thread, request.expectedVersion());
        if (!canResolve(thread, content, actor)) {
            throw new AccessDeniedException("Thread creator, owner or accepted reviewer required");
        }
        boolean resolve = "resolve".equals(request.action());
        if (resolve != "open".equals(thread.getStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        thread.setStatus(resolve ? "resolved" : "open");
        thread.setResolvedBy(resolve ? actor.person().id() : null);
        thread.setResolvedAt(resolve ? Instant.now() : null);
        thread.setResolutionNote(request.note());
        repository.save(thread, actor.person().id());
        repository.flush();
        return threadView(thread, actor);
    }

    @Transactional
    public ReviewView conclude(Long contentId, Conclude request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = open(contentId, actor);
        owner(content, actor);
        policy.version(content, request.expectedContentVersion());
        if (!Objects.equals(content.getCurrentPublishedRevisionId(), request.concludedRevisionId())
            || reviews.openThreads(contentId, request.concludedRevisionId()) != 0) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        payload.markdown(request.conclusion());
        var proposal = repository.require(ReviewProposal.class, contentId);
        proposal.setReviewStatus("concluded");
        proposal.setConcludedRevisionId(request.concludedRevisionId());
        proposal.setConclusion(request.conclusion());
        proposal.setConcludedBy(actor.person().id());
        proposal.setConcludedAt(Instant.now());
        repository.save(proposal, actor.person().id());
        repository.incrementVersion(content, actor.person().id());
        contents.event(content, "review_concluded", actor.person().id());
        repository.flush();
        return view(content, proposal, actor);
    }

    @Transactional
    public ReviewView close(Long contentId, Close request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = open(contentId, actor);
        owner(content, actor);
        policy.version(content, request.expectedContentVersion());
        var proposal = repository.require(ReviewProposal.class, contentId);
        proposal.setReviewStatus("closed");
        proposal.setConclusion(request.reason());
        proposal.setConcludedBy(actor.person().id());
        proposal.setConcludedAt(Instant.now());
        repository.save(proposal, actor.person().id());
        repository.incrementVersion(content, actor.person().id());
        contents.event(content, "review_closed", actor.person().id());
        repository.flush();
        return view(content, proposal, actor);
    }

    private void validateAnchor(CreateThread request) {
        JsonNode anchor = request.anchor();
        String type = anchor.path("type").asText();
        Set<String> fields = switch (type) {
            case "section" -> Set.of("type", "section");
            case "line" -> Set.of("type", "startLine", "endLine");
            case "page" -> Set.of("type", "page");
            case "region" -> Set.of("type", "page", "x", "y", "width", "height");
            default -> throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        };
        if (!anchor.isObject()) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        anchor.fieldNames().forEachRemaining(field -> {
            if (!fields.contains(field)) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        });
        CommunityAsset asset = null;
        if (request.revisionAssetId() != null) {
            var link = repository.require(RevisionAsset.class, request.revisionAssetId());
            if (!Objects.equals(link.getRevisionId(), request.revisionId())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            asset = repository.require(CommunityAsset.class, link.getAssetId());
            if (!"ready".equals(asset.getStatus()) || asset.getDeletedAt() != null) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        }
        if ("section".equals(type)) {
            if (!anchor.path("section").isTextual() || anchor.path("section").asText().isBlank()
                || anchor.path("section").asText().length() > 255) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        } else if ("line".equals(type)) {
            int start = integer(anchor, "startLine");
            int end = integer(anchor, "endLine");
            if (start < 1 || end < start || end > 1_000_000) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        } else {
            int page = integer(anchor, "page");
            if (asset == null || page < 1 || asset.getPageCount() == null || page > asset.getPageCount()) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            if ("region".equals(type)) {
                double x = fraction(anchor, "x");
                double y = fraction(anchor, "y");
                double width = fraction(anchor, "width");
                double height = fraction(anchor, "height");
                if (width == 0 || height == 0 || x + width > 1 || y + height > 1) {
                    throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
                }
            }
        }
    }

    private int integer(JsonNode value, String key) {
        if (!value.path(key).isIntegralNumber() || !value.path(key).canConvertToInt()) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        return value.path(key).intValue();
    }

    private double fraction(JsonNode value, String key) {
        double result = value.path(key).asDouble(Double.NaN);
        if (!value.path(key).isNumber() || !Double.isFinite(result) || result < 0 || result > 1) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        return result;
    }

    private void createProposal(Long contentId, Instant dueAt, Actor actor) {
        if (repository.find(ReviewProposal.class, contentId) == null) {
            ReviewProposal proposal = new ReviewProposal();
            proposal.setContentId(contentId);
            proposal.setReviewStatus("draft");
            proposal.setDueAt(dueAt);
            repository.save(proposal, actor.person().id());
            repository.flush();
        }
    }

    private CommunityContent open(Long contentId, Actor actor) {
        var content = policy.readable(contentId, actor);
        contents.requireKind(content, "architecture_review");
        contents.requirePublished(content);
        policy.requireInteractive(content, actor);
        if (!"open".equals(repository.require(ReviewProposal.class, contentId).getReviewStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        return content;
    }

    private void owner(CommunityContent content, Actor actor) {
        if (!policy.isAuthorOrMaintainer(content, actor)) {
            throw new AccessDeniedException("Review author or maintainer required");
        }
    }

    private void publishedRevision(CommunityContent content, Long revisionId, Actor actor) {
        var revision = repository.require(ContentRevision.class, revisionId);
        policy.requireRevision(content, revision, actor);
        if (!Set.of("published", "superseded").contains(revision.getRevisionStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
    }

    private boolean canResolve(ReviewThread thread, CommunityContent content, Actor actor) {
        return Objects.equals(thread.getCreatedBy(), actor.person().id())
            || policy.isAuthorOrMaintainer(content, actor) || reviews.accepted(content.getContentId(), actor.person()
                .id());
    }

    private ThreadView threadView(ReviewThread thread, Actor actor) {
        var content = policy.readable(thread.getProposalContentId(), actor);
        var root = reviews.rootComment(thread.getThreadId());
        var comment = root == null ? null : comments.view(root);
        boolean writable = reviewActions(content, repository.require(ReviewProposal.class,
            content.getContentId()), actor).contains("comment");
        Map<String, String> names = thread.getResolvedBy() == null ? Map.of()
            : identity.fullNames(List.of(thread.getResolvedBy()));
        return threadView(thread, actor, writable, canResolve(thread, content, actor), comment, names);
    }

    private ThreadView threadView(ReviewThread thread, Actor actor, boolean writable, boolean reviewer,
        CommentView comment, Map<String, String> names) {
        boolean editable = writable && (reviewer || Objects.equals(thread.getCreatedBy(), actor.person().id()));
        return new ThreadView(thread.getThreadId(), thread.getProposalContentId(), thread.getRevisionId(),
            thread.getRevisionAssetId(), thread.getAnchorJson(), thread.getStatus(), person(thread.getResolvedBy(), names),
            thread.getResolvedAt(), thread.getResolutionNote(), comment, thread.getRowVersion(),
            thread.getCreateTime(), thread.getUpdateTime(), editable ? List.of("open".equals(thread.getStatus())
                ? "resolve" : "reopen") : List.of());
    }

    private InvitationView invitationView(ReviewInvitation value, Actor actor) {
        var content = policy.readable(value.getProposalContentId(), actor);
        boolean writable = reviewActions(content, repository.require(ReviewProposal.class,
            content.getContentId()), actor).contains("comment");
        return invitationView(value, actor, content, writable,
            identity.fullNames(List.of(value.getReviewerUserId(), value.getInvitedBy())));
    }

    private InvitationView invitationView(ReviewInvitation value, Actor actor, CommunityContent content,
        boolean writable, Map<String, String> names) {
        boolean current = value.getExpiresAt() == null || value.getExpiresAt().isAfter(Instant.now());
        boolean open = current && writable;
        List<String> capabilities = new ArrayList<>();
        if (open && Objects.equals(value.getReviewerUserId(), actor.person().id())) {
            capabilities.addAll("pending".equals(value.getStatus()) ? List.of("accept", "decline")
                : "accepted".equals(value.getStatus()) ? List.of("complete") : List.of());
        }
        if (open && policy.isAuthorOrMaintainer(content, actor)
            && Set.of("pending", "accepted").contains(value.getStatus())) {
            capabilities.add("cancel");
        }
        return new InvitationView(value.getInvitationId(), value.getProposalContentId(),
            person(value.getReviewerUserId(), names), person(value.getInvitedBy(), names), value.getStatus(), value.getRespondedAt(),
            value.getExpiresAt(), value.getRowVersion(), value.getCreateTime(), value.getUpdateTime(), capabilities);
    }

    private ReviewView view(CommunityContent content, ReviewProposal proposal, Actor actor) {
        return new ReviewView(contents.detail(content, actor, true), proposal.getReviewStatus(), proposal.getDueAt(),
            proposal.getConcludedRevisionId(), proposal.getConclusion(), person(proposal.getConcludedBy()),
            proposal.getConcludedAt(), reviews.openThreads(content.getContentId(),
                content.getCurrentPublishedRevisionId()), reviewActions(content, proposal, actor));
    }

    private List<String> reviewActions(CommunityContent content, ReviewProposal proposal, Actor actor) {
        List<String> actions = new ArrayList<>();
        try {
            policy.requireInteractive(content, actor);
            boolean owner = policy.isAuthorOrMaintainer(content, actor);
            if (owner && content.getCurrentPublishedRevisionId() != null
                && "draft".equals(proposal.getReviewStatus())) {
                actions.add("open");
            }
            if (content.getCurrentPublishedRevisionId() != null && "open".equals(proposal.getReviewStatus())) {
                actions.add("comment");
                if (owner) {
                    actions.addAll(List.of("invite", "close"));
                    if (reviews.openThreads(content.getContentId(), content.getCurrentPublishedRevisionId()) == 0) {
                        actions.add("conclude");
                    }
                }
            }
        } catch (BusinessException exception) {
            // Read-only proposals remain readable, with no mutation controls.
        }
        if (Objects.equals(content.getAuthorUserId(), actor.person().id())
            && Set.of("concluded", "closed").contains(proposal.getReviewStatus())
            && "active".equals(content.getLifecycleStatus())) {
            actions.add("follow_up");
        }
        return List.copyOf(actions);
    }

    private PersonRef person(String userId, Map<String, String> names) {
        return userId == null ? null : new PersonRef(userId, names.get(userId));
    }

    private PersonRef person(String userId) {
        return userId == null ? null : new PersonRef(userId, identity.fullNames(List.of(userId)).get(userId));
    }

    private void validDue(Instant dueAt) {
        if (dueAt != null && !dueAt.isAfter(Instant.now())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
    }
}
