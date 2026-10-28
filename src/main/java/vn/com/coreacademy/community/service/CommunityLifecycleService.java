package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.dto.ContentRequests.LifecycleRequest;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.entity.Answer;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.Question;
import vn.com.coreacademy.community.entity.ReviewProposal;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityRelations;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;

@Service
@RequiredArgsConstructor
public class CommunityLifecycleService {
    private final CommunityRepository repository;
    private final CommunityRelations relations;
    private final CommunityContentService contents;
    private final CommunityPolicy policy;
    private final IdentityWriteLock writeLock;

    /** Xóa mềm answer phải bỏ accept nguyên tử; unarchive lấy trạng thái trước archive, không tự mở question. */
    @Transactional
    public ContentDetail change(Long contentId, LifecycleRequest request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        if (!policy.isAuthorOrMaintainer(content, actor)) {
            throw new AccessDeniedException("Content author or maintainer required");
        }
        policy.version(content, request.expectedContentVersion());
        String previous = content.getLifecycleStatus();
        switch (request.action()) {
            case "open_review" -> {
                contents.requireKind(content, "architecture_review");
                policy.requireInteractive(content, actor);
                contents.requirePublished(content);
                var proposal = repository.require(ReviewProposal.class, contentId);
                requireState(proposal.getReviewStatus(), "draft");
                proposal.setReviewStatus("open");
                repository.save(proposal, actor.person().id());
            }
            case "close" -> {
                contents.requireKind(content, CommunityCodes.QUESTION);
                requireState(previous, CommunityCodes.ACTIVE);
                content.setLifecycleStatus(CommunityCodes.CLOSED);
                Question question = repository.require(Question.class, contentId);
                question.setClosedReason(request.reason());
                repository.save(question, actor.person().id());
            }
            case "reopen" -> {
                contents.requireKind(content, CommunityCodes.QUESTION);
                requireState(previous, CommunityCodes.CLOSED);
                if (relations.moderatorClosed(contentId)) {
                    throw new AccessDeniedException("Moderator case required to reopen");
                }
                content.setLifecycleStatus(CommunityCodes.ACTIVE);
            }
            case "archive" -> {
                if (!Set.of(CommunityCodes.ACTIVE, CommunityCodes.CLOSED).contains(previous)) {
                    throw new BusinessException(CommunityResponseCode.INVALID_STATE);
                }
                content.setLifecycleStatus(CommunityCodes.ARCHIVED);
            }
            case "unarchive" -> {
                requireState(previous, CommunityCodes.ARCHIVED);
                CommunityOutboxEvent event = relations.latestLifecycle(contentId);
                if (event == null || !"archive".equals(event.getPayloadJson().path("action").asText())) {
                    throw new BusinessException(CommunityResponseCode.INVALID_STATE);
                }
                content.setLifecycleStatus(event.getPayloadJson().path("previousStatus").asText());
            }
            case "soft_delete" -> {
                if (CommunityCodes.QUESTION.equals(content.getKind())
                    && relations.otherAnswers(contentId, actor.person().id()) > 0) {
                    throw new BusinessException(CommunityResponseCode.INVALID_STATE);
                }
                content.setDeletedAt(Instant.now());
                content.setDeletedBy(actor.person().id());
                clearAcceptedAnswer(content, actor.person().id());
            }
            default -> throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        repository.incrementVersion(content, actor.person().id());
        CommunityOutboxEvent event = new CommunityOutboxEvent();
        event.setOwnerOrgId(content.getOwnerOrgId());
        event.setContentId(contentId);
        event.setActorUserId(actor.person().id());
        event.setEventType("content_lifecycle");
        event.setPayloadJson(JsonNodeFactory.instance.objectNode().put("action", request.action())
            .put("previousStatus", previous).put("newStatus", content.getLifecycleStatus()));
        event.setDedupKey(UUID.randomUUID().toString());
        event.setEventStatus("pending");
        event.setAvailableAt(Instant.now());
        event.setAttemptCount(0);
        repository.save(event, actor.person().id());
        repository.flush();
        if (content.getDeletedAt() != null) {
            // Tombstone không chứa body, tag, tác giả hoặc file của nội dung đã xóa.
            return new ContentDetail(contentId, content.getKind(), null, null, null, "deleted", null, null,
                content.getRowVersion(), null, null, List.of(), null, null, null, null, false, 0, 0, 0);
        }
        return contents.detail(content, actor, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clearAcceptedAnswer(CommunityContent content, String actorId) {
        if (!CommunityCodes.ANSWER.equals(content.getKind())) {
            return;
        }
        Answer answer = repository.require(Answer.class, content.getContentId());
        Question question = repository.require(Question.class, answer.getQuestionContentId());
        if (Objects.equals(question.getAcceptedAnswerContentId(), content.getContentId())) {
            question.setAcceptedAnswerContentId(null);
            question.setAcceptedAnswerRevisionId(null);
            question.setAcceptedBy(null);
            question.setAcceptedAt(null);
            repository.save(question, actorId);
            repository.incrementVersion(repository.require(CommunityContent.class, question.getContentId()), actorId);
        }
        repository.flush();
        contents.recomputeQuestion(question.getContentId(), actorId);
    }

    private void requireState(String current, String required) {
        if (!required.equals(current)) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
    }
}
