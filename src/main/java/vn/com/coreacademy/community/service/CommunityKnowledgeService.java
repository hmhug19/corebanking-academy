package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateKnowledgeRequest;
import vn.com.coreacademy.community.dto.DocumentRequests.CreatePostmortem;
import vn.com.coreacademy.community.dto.KnowledgeDtos.HelpAction;
import vn.com.coreacademy.community.dto.KnowledgeDtos.HelpRequestView;
import vn.com.coreacademy.community.dto.KnowledgeDtos.KnowledgeAction;
import vn.com.coreacademy.community.dto.KnowledgeDtos.KnowledgeRequestCard;
import vn.com.coreacademy.community.dto.KnowledgeDtos.KnowledgeRequestView;
import vn.com.coreacademy.community.dto.PreferenceDtos.GuildRef;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.ContentLink;
import vn.com.coreacademy.community.entity.Guild;
import vn.com.coreacademy.community.entity.HelpRequest;
import vn.com.coreacademy.community.entity.KnowledgeRequest;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityKnowledgeRepository;
import vn.com.coreacademy.community.repository.CommunityPreferenceRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.community.repository.ContentRepository.DocumentFilter;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityKnowledgeService {
    private final CommunityRepository repository;
    private final CommunityKnowledgeRepository knowledge;
    private final CommunityPreferenceRepository preferences;
    private final CommunityPolicy policy;
    private final CommunityContentService contents;
    private final CommunityGuildService guilds;
    private final CommunityAudience audience;
    private final CommunityPayload payload;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;

    @Transactional
    public HelpRequestView help(Long questionId, vn.com.coreacademy.community.dto.KnowledgeDtos.HelpRequest request) {
        writeLock.acquire();
        var actor = policy.actor();
        var question = policy.readable(questionId, actor);
        contents.requireKind(question, "question");
        contents.requirePublished(question);
        policy.requireInteractive(question, actor);
        if ((request.targetUserId() == null) == (request.targetGuildId() == null)
            || request.dueAt() != null && !request.dueAt().isAfter(Instant.now())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        if (request.targetUserId() != null) {
            var target = identity.actor(request.targetUserId());
            if (!Objects.equals(target.person().orgId(), actor.person().orgId()) || !policy.canRead(question, target)
                || preferences.expertise(target.person().id()).stream()
                    .noneMatch(skill -> skill.getAvailableForHelp())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        } else {
            guilds.readable(request.targetGuildId(), actor);
        }
        var existing = knowledge.openHelp(questionId, request.targetUserId(), request.targetGuildId());
        if (!existing.isEmpty()) {
            return helpView(existing.get(0));
        }
        HelpRequest help = new HelpRequest();
        help.setQuestionContentId(questionId);
        help.setRequestedBy(actor.person().id());
        help.setTargetUserId(request.targetUserId());
        help.setTargetGuildId(request.targetGuildId());
        help.setStatus("pending");
        help.setDueAt(request.dueAt());
        repository.save(help, actor.person().id());
        var eventData = JsonNodeFactory.instance.objectNode();
        eventData.put("helpRequestId", help.getHelpRequestId());
        if (help.getTargetUserId() != null) {
            eventData.putArray("recipientUserIds").add(help.getTargetUserId());
        }
        if (help.getTargetGuildId() != null) {
            eventData.put("targetGuildId", help.getTargetGuildId());
        }
        contents.event(question, "help_requested", actor.person().id(), eventData);
        repository.flush();
        return helpView(help);
    }

    @Transactional
    public HelpRequestView helpAction(Long helpId, HelpAction request) {
        writeLock.acquire();
        var actor = policy.actor();
        var help = repository.require(HelpRequest.class, helpId);
        var question = policy.readable(help.getQuestionContentId(), actor);
        policy.version(help, request.expectedVersion());
        boolean recipient = Objects.equals(help.getTargetUserId(), actor.person().id())
            || help.getTargetGuildId() != null && policy.member(help.getTargetGuildId(), actor);
        switch (request.action()) {
            case "accept", "decline" -> {
                policy.requireInteractive(question, actor);
                if (help.getDueAt() != null && !help.getDueAt().isAfter(Instant.now())) {
                    throw new BusinessException(CommunityResponseCode.INVALID_STATE);
                }
                if (!recipient || !"pending".equals(help.getStatus())) {
                    throw new AccessDeniedException("Pending help recipient required");
                }
                help.setStatus("accept".equals(request.action()) ? "accepted" : "declined");
            }
            case "resolve" -> {
                policy.requireAuthor(question, actor);
                if (!Set.of("pending", "accepted").contains(help.getStatus())) {
                    throw new BusinessException(CommunityResponseCode.INVALID_STATE);
                }
                help.setStatus("resolved");
            }
            case "cancel" -> {
                if (!Objects.equals(help.getRequestedBy(), actor.person().id())
                    || !Set.of("pending", "accepted").contains(help.getStatus())) {
                    throw new AccessDeniedException("Open help requester required");
                }
                help.setStatus("cancelled");
            }
            default -> throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        help.setRespondedBy(actor.person().id());
        help.setRespondedAt(Instant.now());
        repository.save(help, actor.person().id());
        var eventData = JsonNodeFactory.instance.objectNode();
        eventData.put("helpRequestId", help.getHelpRequestId());
        eventData.putArray("recipientUserIds").add(help.getRequestedBy());
        contents.event(question, "help_responded", actor.person().id(), eventData);
        repository.flush();
        return helpView(help);
    }

    @Transactional
    public KnowledgeRequestView createRequest(CreateKnowledgeRequest request) {
        writeLock.acquire();
        var actor = policy.actor();
        if (request.dueAt() != null && !request.dueAt().isAfter(Instant.now())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        var detail = contents.create("knowledge_request", null, request.content(request.primaryGuildId()),
            payload.hash("knowledge_request", request));
        var value = repository.find(KnowledgeRequest.class, detail.contentId());
        if (value == null) {
            value = new KnowledgeRequest();
            value.setContentId(detail.contentId());
            value.setRequestStatus("open");
            value.setDueAt(request.dueAt());
            repository.save(value, actor.person().id());
            repository.flush();
        }
        return requestView(value, actor, true);
    }

    public PageResponse<KnowledgeRequestCard> requests(String q, String status, Long guildId, int page, int size) {
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        return CommunityPages.map(contents.list(new ContentRepository.Search("knowledge_request", q, null, null,
            null, false, null, null, page, size, "publishedAt", guildId,
            new DocumentFilter(null, false, null, null, null, status, null, null, null, null, false))), card -> {
                var value = repository.require(KnowledgeRequest.class, card.contentId());
                return new KnowledgeRequestCard(card, effectiveStatus(value), person(value.getClaimedBy()),
                    value.getDueAt(), "fulfilled".equals(effectiveStatus(value)) ? value.getFulfilledAt() : null);
            });
    }

    @Transactional
    public KnowledgeRequestView action(Long contentId, KnowledgeAction request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        contents.requireKind(content, "knowledge_request");
        contents.requirePublished(content);
        policy.requireOperational(content, actor);
        policy.version(content, request.expectedContentVersion());
        var value = repository.require(KnowledgeRequest.class, contentId);
        boolean owner = policy.isAuthorOrMaintainer(content, actor);
        boolean claimant = Objects.equals(value.getClaimedBy(), actor.person().id());
        String status = effectiveStatus(value);
        if (!"fulfill".equals(request.action()) && request.fulfillmentContentIds() != null
            && !request.fulfillmentContentIds().isEmpty()) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        switch (request.action()) {
            case "claim" -> {
                if (!"open".equals(status)) {
                    throw new BusinessException(CommunityResponseCode.CONFLICT);
                }
                value.setClaimedBy(actor.person().id());
                value.setClaimedAt(Instant.now());
                value.setRequestStatus("claimed");
            }
            case "release" -> {
                if (!Set.of("claimed", "fulfilled").contains(status) || !owner && !claimant) {
                    throw new AccessDeniedException("Request claimant or owner required");
                }
                value.setClaimedBy(null);
                value.setClaimedAt(null);
                value.setFulfilledAt(null);
                value.setRequestStatus("open");
                knowledge.fulfillments(contentId).forEach(repository::remove);
            }
            case "fulfill" -> {
                if (!Set.of("open", "claimed", "fulfilled").contains(status) || !owner && !claimant) {
                    throw new AccessDeniedException("Request claimant or owner required");
                }
                var ids = request.fulfillmentContentIds();
                if (ids == null || ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) {
                    throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
                }
                for (Long id : ids) {
                    var target = policy.readable(id, actor);
                    if (!audience.covered(content, target)) {
                        throw BusinessException.validation("fulfillmentContentIds", "AUDIENCE_NOT_COVERED",
                            "Tài liệu phải bao phủ đối tượng được đọc yêu cầu.");
                    }
                }
                knowledge.fulfillments(contentId).forEach(repository::remove);
                for (Long id : ids) {
                    ContentLink link = new ContentLink();
                    link.setSourceContentId(contentId);
                    link.setTargetContentId(id);
                    link.setRelationType("fulfills");
                    repository.save(link, actor.person().id());
                }
                value.setRequestStatus("fulfilled");
                value.setFulfilledAt(Instant.now());
            }
            case "close", "reopen" -> {
                if (!owner || request.reason() == null || request.reason().isBlank()) {
                    throw new AccessDeniedException("Request owner and reason required");
                }
                if ("reopen".equals(request.action()) && !"closed".equals(status)
                    || "close".equals(request.action()) && "closed".equals(status)) {
                    throw new BusinessException(CommunityResponseCode.INVALID_STATE);
                }
                value.setRequestStatus("close".equals(request.action()) ? "closed"
                    : value.getClaimedBy() == null ? "open" : "claimed");
                value.setClosedReason(request.reason());
            }
            default -> throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        repository.save(value, actor.person().id());
        repository.incrementVersion(content, actor.person().id());
        contents.event(content, "knowledge_request_changed", actor.person().id());
        repository.flush();
        return requestView(value, actor, false);
    }

    @Transactional
    public ContentDetail postmortem(CreatePostmortem request) {
        return contents.create("lesson_learned", null, request.content(request.primaryGuildId()),
            payload.hash("lesson_learned", request));
    }

    public PageResponse<ContentCard> lessons(String q, List<Long> tagIds, Long guildId, int page, int size) {
        return contents.list(new ContentRepository.Search("lesson_learned", q, tagIds, null, null, false,
            null, null, page, size, "publishedAt", guildId));
    }

    public PageResponse<ContentCard> feed(boolean following, Long guildId, String kind, int page, int size) {
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        return contents.list(new ContentRepository.Search(kind, null, null, null, null, false,
            null, null, page, size, "lastActivityAt", guildId,
            new DocumentFilter(null, false, null, null, null, null, null, null, null, null, following)));
    }

    private String effectiveStatus(KnowledgeRequest value) {
        if (!"fulfilled".equals(value.getRequestStatus())) {
            return value.getRequestStatus();
        }
        var request = repository.require(CommunityContent.class, value.getContentId());
        boolean eligible = knowledge.fulfillments(value.getContentId()).stream().anyMatch(link ->
            audience.covered(request, repository.require(CommunityContent.class, link.getTargetContentId())));
        return eligible ? "fulfilled" : value.getClaimedBy() == null ? "open" : "claimed";
    }

    private KnowledgeRequestView requestView(KnowledgeRequest value, Actor actor, boolean includeDraft) {
        var content = policy.readable(value.getContentId(), actor);
        var refs = knowledge.fulfillments(value.getContentId()).stream()
            .map(link -> repository.require(CommunityContent.class, link.getTargetContentId()))
            .filter(target -> policy.canRead(target, actor) && audience.covered(content, target))
            .map(target -> new ContentRef(target.getContentId(), target.getKind(),
                target.getCurrentPublishedRevisionId(),
                contents.detail(target, actor, false).publishedRevision().title(),
                "/community/contents/" + target.getContentId())).toList();
        return new KnowledgeRequestView(contents.detail(content, actor, includeDraft), effectiveStatus(value),
            person(value.getClaimedBy()), value.getClaimedAt(), value.getDueAt(),
            "fulfilled".equals(effectiveStatus(value)) ? value.getFulfilledAt() : null, refs);
    }

    private PersonRef person(String id) {
        return id == null ? null : new PersonRef(id, identity.fullNames(List.of(id)).get(id));
    }

    private HelpRequestView helpView(HelpRequest value) {
        var actor = policy.actor();
        var question = policy.readable(value.getQuestionContentId(), actor);
        boolean recipient = Objects.equals(value.getTargetUserId(), actor.person().id())
            || value.getTargetGuildId() != null && policy.member(value.getTargetGuildId(), actor);
        boolean open = Set.of("pending", "accepted").contains(value.getStatus());
        boolean pending = "pending".equals(value.getStatus()) && recipient
            && "active".equals(question.getLifecycleStatus())
            && (value.getDueAt() == null || value.getDueAt().isAfter(Instant.now()));
        GuildRef guild = value.getTargetGuildId() == null ? null : new GuildRef(value.getTargetGuildId(),
            repository.require(Guild.class, value.getTargetGuildId()).getName());
        return new HelpRequestView(value.getHelpRequestId(), value.getQuestionContentId(),
            person(value.getRequestedBy()),
            person(value.getTargetUserId()), guild, value.getStatus(), value.getDueAt(), person(value.getRespondedBy()),
            value.getRespondedAt(), value.getRowVersion(), value.getCreateTime(), value.getUpdateTime(),
            Map.of("canAccept", pending, "canDecline", pending, "canResolve", open
                && Objects.equals(question.getAuthorUserId(), actor.person().id()), "canCancel", open
                    && Objects.equals(value.getRequestedBy(), actor.person().id())));
    }
}
