package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.config.CommunityProperties;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.dto.ContentRequests.AcceptRequest;
import vn.com.coreacademy.community.dto.ContentRequests.AccessRequest;
import vn.com.coreacademy.community.dto.ContentRequests.AssetInput;
import vn.com.coreacademy.community.dto.ContentRequests.CreateContent;
import vn.com.coreacademy.community.dto.ContentRequests.PublishRequest;
import vn.com.coreacademy.community.dto.ContentRequests.SaveDraft;
import vn.com.coreacademy.community.dto.ContentRequests.TagsRequest;
import vn.com.coreacademy.community.dto.ContentViews.AccessView;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.ContentViews.Capabilities;
import vn.com.coreacademy.community.dto.ContentViews.Closure;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.ContentViews.RevisionDetail;
import vn.com.coreacademy.community.dto.ContentViews.TagView;
import vn.com.coreacademy.community.dto.ContentViews.VoteView;
import vn.com.coreacademy.community.entity.Answer;
import vn.com.coreacademy.community.entity.CommunityAsset;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.CommunitySubscription;
import vn.com.coreacademy.community.entity.CommunityTag;
import vn.com.coreacademy.community.entity.CommunityVote;
import vn.com.coreacademy.community.entity.ContentAccessGrant;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.ContentTag;
import vn.com.coreacademy.community.entity.Guild;
import vn.com.coreacademy.community.entity.Question;
import vn.com.coreacademy.community.entity.QuestionLink;
import vn.com.coreacademy.community.entity.RevisionAsset;
import vn.com.coreacademy.community.entity.TagAlias;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.mapper.CommunityMapper;
import vn.com.coreacademy.community.repository.CommunityCollectionRepository;
import vn.com.coreacademy.community.repository.CommunityManagementRepository;
import vn.com.coreacademy.community.repository.CommunityMediaRepository;
import vn.com.coreacademy.community.repository.CommunityPreferenceRepository;
import vn.com.coreacademy.community.repository.CommunityRelations;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.CommunityTagReadRepository;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

/** Draft/publish/accept dùng cùng khóa Identity để membership và quan hệ scalar không đổi giữa kiểm tra và ghi. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityContentService {
    private final CommunityRepository repository;
    private final ContentRepository contents;
    private final CommunityRelations relations;
    private final CommunityPolicy policy;
    private final CommunityMapper mapper;
    private final CommunityPayload payload;
    private final IdentityWriteLock writeLock;
    private final LearningIdentityService identity;
    private final IdentityAuditService audit;
    private final CommunityCardService cards;
    private final CommunityTagReadRepository tagRead;
    private final CommunityMediaRepository media;
    private final CommunityProperties mediaProperties;
    private final CommunityCollectionRepository collections;
    private final CommunityPreferenceRepository preferences;
    private final CommunityManagementRepository management;

    public PageResponse<ContentCard> list(ContentRepository.Search search) {
        return list(search, false);
    }

    public PageResponse<ContentCard> list(ContentRepository.Search search, boolean publisherOnly) {
        Actor actor = policy.actor();
        if (search.questionId() != null) {
            CommunityContent parent = policy.readable(search.questionId(), actor);
            requireKind(parent, CommunityCodes.QUESTION);
            requirePublished(parent);
        }
        var page = contents.search(search, actor.person().id(), actor.person().orgId(), publisherOnly);
        List<Long> revisionIds = page.stream().map(content -> content.getCurrentPublishedRevisionId() != null
            ? content.getCurrentPublishedRevisionId() : content.getCurrentDraftRevisionId()).toList();
        Map<Long, ContentRevision> revisions = contents.revisionBatch(revisionIds).stream()
            .collect(Collectors.toMap(ContentRevision::getRevisionId, Function.identity()));
        var raw = page.map(content -> mapper.card(content,
            revisions.get(content.getCurrentPublishedRevisionId() != null
                ? content.getCurrentPublishedRevisionId() : content.getCurrentDraftRevisionId())));
        return CommunityPages.of(cards.enrich(raw.getContent()), page.getNumber(), page.getSize(),
            page.getTotalElements());
    }

    public ContentCard card(Long contentId) {
        var content = policy.readable(contentId, policy.actor());
        var revision = repository.require(ContentRevision.class, content.getCurrentPublishedRevisionId() != null
            ? content.getCurrentPublishedRevisionId() : content.getCurrentDraftRevisionId());
        return cards.enrich(List.of(mapper.card(content, revision))).get(0);
    }

    public List<ContentCard> cards(List<Long> ids) {
        var values = contents.publishedBatch(ids, policy.actor());
        var revisions = contents.revisionBatch(values.stream().map(CommunityContent::getCurrentPublishedRevisionId)
            .toList())
            .stream().collect(Collectors.toMap(ContentRevision::getRevisionId, Function.identity()));
        var result = cards.enrich(values.stream().map(content -> mapper.card(content,
            revisions.get(content.getCurrentPublishedRevisionId()))).toList()).stream()
            .collect(Collectors.toMap(ContentCard::contentId, Function.identity()));
        return ids.stream().filter(result::containsKey).map(result::get).toList();
    }

    public ContentDetail detail(Long contentId, boolean includeOwnDraft) {
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        return detail(content, actor, includeOwnDraft);
    }

    public ContentDetail question(Long contentId, boolean includeOwnDraft) {
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        requireKind(content, CommunityCodes.QUESTION);
        return detail(content, actor, includeOwnDraft);
    }

    /** Kiểm lại quyền trước replay idempotent; retry không thể mở lại nội dung đã bị thu hồi quyền. */
    @Transactional
    public ContentDetail create(String kind, Long questionId, CreateContent request) {
        return create(kind, questionId, request, null);
    }

    @Transactional
    public ContentDetail create(String kind, Long questionId, CreateContent request, String canonicalHash) {
        // Bước 1: khóa trước khi đọc caller/quan hệ để thay đổi Identity không chen giữa kiểm tra và ghi.
        writeLock.acquire();
        Actor actor = policy.actor();
        String hash = canonicalHash == null ? payload.hash(kind + ":" + questionId, request) : canonicalHash;
        var existing = contents.byRequest(actor.person().id(), request.clientRequestId());
        // Bước 2: replay chỉ trả bản cũ sau kiểm tra quyền hiện tại và hash payload, không tạo lại draft.
        if (!existing.isEmpty()) {
            CommunityContent content = policy.readable(existing.get(0).getContentId(), actor);
            if (!hash.equals(content.getRequestHash())) {
                throw new BusinessException(CommunityResponseCode.IDEMPOTENCY_CONFLICT);
            }
            return detail(content, actor, true);
        }

        CommunityContent parent = null;
        // Bước 3: answer phải có question published, active; ACL/tag không lấy từ payload answer.
        if (CommunityCodes.ANSWER.equals(kind)) {
            parent = policy.readable(questionId, actor);
            requireKind(parent, CommunityCodes.QUESTION);
            requirePublished(parent);
            policy.requireInteractive(parent, actor);
            if (request.visibility() != null || request.primaryGuildId() != null
                || request.tagIds() != null && !request.tagIds().isEmpty()) {
                throw BusinessException.validation("visibility", "INHERITED", "Answer kế thừa ACL và tag câu hỏi.");
            }
        }
        CommunityContent content = new CommunityContent();
        // Bước 4: ghi root và draft bằng ID scalar; mọi tham chiếu con cùng transaction, lỗi sẽ rollback.
        content.setKind(kind);
        content.setOwnerOrgId(actor.person().orgId());
        content.setAuthorUserId(actor.person().id());
        content.setMaintainerUserId(actor.person().id());
        content.setVisibility(parent == null ? request.visibility() : parent.getVisibility());
        content.setPrimaryGuildId(parent == null ? request.primaryGuildId() : parent.getPrimaryGuildId());
        if (parent == null) {
            visibility(content.getVisibility(), content.getPrimaryGuildId(), actor);
        }
        content.setLifecycleStatus(CommunityCodes.ACTIVE);
        content.setLastActivityAt(Instant.now());
        content.setClientRequestId(request.clientRequestId());
        content.setRequestHash(hash);
        repository.save(content, actor.person().id());
        repository.flush();

        ContentRevision revision = new ContentRevision();
        revision.setContentId(content.getContentId());
        revision.setRevisionNo(1);
        revision.setRevisionStatus(CommunityCodes.DRAFT);
        revision.setTitle(request.title());
        revision.setBodyMarkdown(request.bodyMarkdown());
        revision.setSummary(request.summary());
        revision.setContextJson(payload.context(request.context(), kind));
        payload.markdown(request.bodyMarkdown());
        repository.save(revision, actor.person().id());
        repository.flush();
        content.setCurrentDraftRevisionId(revision.getRevisionId());
        replaceAssets(content, revision, request.assets(), actor);
        if (CommunityCodes.QUESTION.equals(kind)) {
            Question question = new Question();
            question.setContentId(content.getContentId());
            question.setResolutionStatus(CommunityCodes.UNANSWERED);
            repository.save(question, actor.person().id());
        } else if (parent != null) {
            Answer answer = new Answer();
            answer.setContentId(content.getContentId());
            answer.setQuestionContentId(parent.getContentId());
            repository.save(answer, actor.person().id());
        }
        replaceTags(content, request.tagIds(), actor);
        repository.save(content, actor.person().id());
        repository.flush();
        return detail(content, actor, true);
    }

    @Transactional
    public RevisionDetail draft(Long contentId, Long expectedVersion) {
        writeLock.acquire();
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        policy.requireEdit(content, actor);
        if (content.getCurrentDraftRevisionId() != null) {
            ContentRevision current = repository.require(ContentRevision.class, content.getCurrentDraftRevisionId());
            if (CommunityCodes.DRAFT.equals(current.getRevisionStatus())) {
                return revision(current, content);
            }
            if (CommunityCodes.SUBMITTED.equals(current.getRevisionStatus())) {
                throw new BusinessException(CommunityResponseCode.INVALID_STATE);
            }
        }
        policy.version(content, expectedVersion);
        List<ContentRevision> history = contents.revisions(contentId);
        ContentRevision source = history.get(0);
        ContentRevision draft = new ContentRevision();
        draft.setContentId(contentId);
        draft.setRevisionNo(source.getRevisionNo() + 1);
        draft.setRevisionStatus(CommunityCodes.DRAFT);
        draft.setTitle(source.getTitle());
        draft.setBodyMarkdown(source.getBodyMarkdown());
        draft.setSummary(source.getSummary());
        draft.setContextJson(source.getContextJson().deepCopy());
        repository.save(draft, actor.person().id());
        repository.flush();
        for (RevisionAsset asset : relations.assets(source.getRevisionId())) {
            RevisionAsset copy = new RevisionAsset();
            copy.setRevisionId(draft.getRevisionId());
            copy.setAssetId(asset.getAssetId());
            copy.setUsage(asset.getUsage());
            copy.setCaption(asset.getCaption());
            copy.setAltText(asset.getAltText());
            copy.setSortNo(asset.getSortNo());
            repository.save(copy, actor.person().id());
        }
        content.setCurrentDraftRevisionId(draft.getRevisionId());
        repository.save(content, actor.person().id());
        repository.flush();
        return revision(draft, content);
    }

    @Transactional
    public RevisionDetail saveDraft(Long contentId, SaveDraft request) {
        writeLock.acquire();
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        policy.requireEdit(content, actor);
        policy.version(content, request.expectedContentVersion());
        ContentRevision draft = editableDraft(content);
        policy.version(draft, request.expectedRevisionVersion());
        payload.markdown(request.bodyMarkdown());
        draft.setTitle(request.title());
        draft.setBodyMarkdown(request.bodyMarkdown());
        draft.setSummary(request.summary());
        draft.setContextJson(payload.context(request.context(), content.getKind()));
        draft.setChangeNote(request.changeNote());
        replaceAssets(content, draft, request.assets(), actor);
        repository.save(draft, actor.person().id());
        // Chỉ sửa liên kết asset cũng phải thay version của content và draft để FE phát hiện stale write.
        repository.incrementVersion(draft, actor.person().id());
        repository.incrementVersion(content, actor.person().id());
        audit.record(actor.person().id(), "COMMUNITY_REVISION_EDITED", "community_revision",
            draft.getRevisionId().toString(), content.getOwnerOrgId(), Map.of("contentId", contentId));
        repository.flush();
        return revision(draft, content);
    }

    @Transactional
    public ContentDetail publish(Long contentId, PublishRequest request) {
        writeLock.acquire();
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        policy.requirePublisher(content, actor);
        policy.version(content, request.expectedContentVersion());
        ContentRevision draft = editableDraft(content);
        policy.version(draft, request.expectedRevisionVersion());
        if (CommunityCodes.REVIEWED_KINDS.contains(content.getKind())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        validatePublish(content, draft, actor);
        publishRevision(content, draft, actor);
        if (CommunityCodes.ANSWER.equals(content.getKind())) {
            Answer answer = repository.require(Answer.class, contentId);
            recomputeQuestion(answer.getQuestionContentId(), actor.person().id());
        }
        repository.flush();
        return detail(content, actor, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void validatePublish(CommunityContent content, ContentRevision revision, Actor actor) {
        CommunityDocumentContext.validate(revision.getContextJson(), content.getKind(), true);
        var covers = relations.assets(revision.getRevisionId()).stream()
            .filter(asset -> "cover".equals(asset.getUsage())).toList();
        if (covers.size() > 1 || covers.stream().anyMatch(cover -> {
            var image = repository.require(CommunityAsset.class, cover.getAssetId());
            return !"image".equals(image.getMediaType()) || image.getMediaCheckedAt() == null;
        })) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        if ("recording".equals(content.getKind())) {
            var primary = relations.assets(revision.getRevisionId()).stream()
                .filter(value -> "primary_video".equals(value.getUsage())).toList();
            if (primary.size() != 1 || !"video".equals(repository.require(CommunityAsset.class,
                primary.get(0).getAssetId()).getMediaType())) {
                throw new BusinessException(CommunityResponseCode.NOT_PUBLISHABLE);
            }
        }
        if (revision.getTitle().trim().length() < 5 || revision.getBodyMarkdown().trim().length() < 20
            || CommunityCodes.QUESTION.equals(content.getKind())
                && relations.contentTags(content.getContentId()).isEmpty()) {
            throw new BusinessException(CommunityResponseCode.NOT_PUBLISHABLE);
        }
        payload.markdown(revision.getBodyMarkdown());
        validateMentions(content, revision.getBodyMarkdown());
        for (ContentTag link : relations.contentTags(content.getContentId())) {
            CommunityTag tag = repository.require(CommunityTag.class, link.getTagId());
            if (!CommunityCodes.ACTIVE.equals(tag.getStatus())
                || !content.getOwnerOrgId().equals(tag.getOwnerOrgId())) {
                throw new BusinessException(CommunityResponseCode.NOT_PUBLISHABLE);
            }
        }
        for (RevisionAsset asset : relations.assets(revision.getRevisionId())) {
            // Quyền công bố đã kiểm khi attach; người duyệt độc lập không phải uploader của tệp tác giả.
            var file = repository.require(CommunityAsset.class, asset.getAssetId());
            if (!Objects.equals(file.getOwnerOrgId(), content.getOwnerOrgId()) || file.getDeletedAt() != null
                || !"ready".equals(file.getStatus())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        }
    }

    /** Payload revision cũ giữ nguyên; chỉ chuyển trạng thái và published pointer cùng transaction với outbox. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishRevision(CommunityContent content, ContentRevision revision, Actor actor) {
        if (content.getCurrentPublishedRevisionId() != null) {
            ContentRevision previous = repository.require(ContentRevision.class,
                content.getCurrentPublishedRevisionId());
            previous.setRevisionStatus(CommunityCodes.SUPERSEDED);
            repository.save(previous, actor.person().id());
        }
        revision.setRevisionStatus(CommunityCodes.PUBLISHED);
        revision.setPublishedAt(Instant.now());
        revision.setPublishedBy(actor.person().id());
        content.setCurrentPublishedRevisionId(revision.getRevisionId());
        content.setCurrentDraftRevisionId(null);
        content.setLastActivityAt(Instant.now());
        repository.save(revision, actor.person().id());
        repository.save(content, actor.person().id());
        event(content, "content_published", actor.person().id(), JsonNodeFactory.instance.objectNode()
            .put("revisionId", revision.getRevisionId()));
    }

    public void validateMentions(CommunityContent content, String body) {
        for (String id : payload.mentions(body)) {
            var recipient = identity.actor(id);
            if (!Objects.equals(recipient.person().orgId(), content.getOwnerOrgId()) || !policy.canRead(content,
                recipient)) {
                throw BusinessException.validation("bodyMarkdown", "MENTION_NOT_READABLE",
                    "Người được nhắc tên phải cùng đơn vị và có quyền đọc nguồn.");
            }
        }
    }

    @Transactional
    public ContentDetail accept(Long questionId, AcceptRequest request) {
        writeLock.acquire();
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(questionId, actor);
        requireKind(content, CommunityCodes.QUESTION);
        policy.requireAuthor(content, actor);
        requirePublished(content);
        policy.version(content, request.expectedContentVersion());
        if (!Set.of(CommunityCodes.ACTIVE, CommunityCodes.CLOSED).contains(content.getLifecycleStatus())
            || (request.answerContentId() == null) != (request.answerRevisionId() == null)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        Question question = repository.require(Question.class, questionId);
        var acceptEvent = JsonNodeFactory.instance.objectNode();
        var recipients = acceptEvent.putArray("recipientUserIds");
        if (question.getAcceptedAnswerContentId() != null) {
            recipients.add(repository.require(CommunityContent.class, question.getAcceptedAnswerContentId())
                .getAuthorUserId());
        }
        if (request.answerContentId() != null) {
            CommunityContent answer = policy.readable(request.answerContentId(), actor);
            requireKind(answer, CommunityCodes.ANSWER);
            Answer relation = repository.require(Answer.class, answer.getContentId());
            if (!Objects.equals(relation.getQuestionContentId(), questionId)
                || !Objects.equals(answer.getCurrentPublishedRevisionId(), request.answerRevisionId())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            recipients.add(answer.getAuthorUserId());
        }
        question.setAcceptedAnswerContentId(request.answerContentId());
        question.setAcceptedAnswerRevisionId(request.answerRevisionId());
        question.setAcceptedBy(request.answerContentId() == null ? null : actor.person().id());
        question.setAcceptedAt(request.answerContentId() == null ? null : Instant.now());
        repository.save(question, actor.person().id());
        recomputeQuestion(questionId, actor.person().id());
        repository.incrementVersion(content, actor.person().id());
        event(content, "answer_accept_changed", actor.person().id(), acceptEvent);
        repository.flush();
        return detail(content, actor, false);
    }

    @Transactional
    public VoteView vote(Long contentId, int value) {
        writeLock.acquire();
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        requirePublished(content);
        policy.requireInteractive(content, actor);
        if (Objects.equals(content.getAuthorUserId(), actor.person().id())) {
            throw new BusinessException(CommunityResponseCode.SELF_VOTE);
        }
        if (!CommunityCodes.VOTABLE_KINDS.contains(content.getKind()) || value < -1 || value > 1) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        List<CommunityVote> own = relations.ownVotes(contentId, actor.person().id());
        int previous = own.isEmpty() ? 0 : own.get(0).getValue();
        if (previous != value) {
            if (value == 0) {
                own.forEach(repository::remove);
            } else {
                CommunityVote vote = own.isEmpty() ? new CommunityVote() : own.get(0);
                vote.setContentId(contentId);
                vote.setUserId(actor.person().id());
                vote.setValue((short) value);
                repository.save(vote, actor.person().id());
            }
            event(content, "vote_changed", actor.person().id());
        }
        repository.flush();
        Object[] totals = relations.voteTotals(contentId);
        return new VoteView(contentId, ((Number) totals[0]).longValue(), ((Number) totals[1]).longValue(),
            ((Number) totals[2]).longValue(), value);
    }

    @Transactional
    public ContentDetail tags(Long contentId, TagsRequest request) {
        writeLock.acquire();
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        policy.requirePublisher(content, actor);
        policy.version(content, request.expectedContentVersion());
        if (CommunityCodes.ANSWER.equals(content.getKind())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        replaceTags(content, request.tagIds(), actor);
        repository.incrementVersion(content, actor.person().id());
        event(content, "content_tags_changed", actor.person().id());
        repository.flush();
        return detail(content, actor, true);
    }

    @Transactional
    public AccessView access(Long contentId, AccessRequest request) {
        writeLock.acquire();
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        policy.requireAuthor(content, actor);
        policy.requireInteractive(content, actor);
        policy.version(content, request.expectedContentVersion());
        requireNonAnswer(content);
        visibility(request.visibility(), request.primaryGuildId(), actor);
        if (!CommunityCodes.RESTRICTED.equals(request.visibility()) && !request.grants().isEmpty()) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        Set<String> subjects = new HashSet<>();
        for (var input : request.grants()) {
            if ((input.userId() == null) == (input.guildId() == null)
                || input.expiresAt() != null && !input.expiresAt().isAfter(Instant.now())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            if (input.userId() != null) {
                var person = identity.activePerson(input.userId());
                if (!Objects.equals(person.orgId(), content.getOwnerOrgId())
                    || Objects.equals(content.getAuthorUserId(), input.userId())
                    || Objects.equals(content.getMaintainerUserId(), input.userId())) {
                    throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
                }
            } else {
                sameOrgGuild(input.guildId(), actor);
            }
            if (!subjects.add(input.userId() == null ? "g:" + input.guildId() : "u:" + input.userId())) {
                throw new BusinessException(CommunityResponseCode.DUPLICATE);
            }
        }
        relations.grants(contentId).forEach(repository::remove);
        for (var input : request.grants()) {
            ContentAccessGrant grant = new ContentAccessGrant();
            grant.setContentId(contentId);
            grant.setUserId(input.userId());
            grant.setGuildId(input.guildId());
            grant.setPermission(input.permission());
            grant.setExpiresAt(input.expiresAt());
            grant.setStatus(CommunityCodes.ACTIVE);
            repository.save(grant, actor.person().id());
        }
        content.setVisibility(request.visibility());
        content.setPrimaryGuildId(request.primaryGuildId());
        repository.incrementVersion(content, actor.person().id());
        event(content, "content_access_changed", actor.person().id());
        repository.flush();
        return new AccessView(contentId, content.getVisibility(), content.getPrimaryGuildId(),
            relations.grants(contentId).stream().map(mapper::grant).toList(), content.getRowVersion(),
            Map.of("canManageAccess", true));
    }

    @Transactional
    public RevisionDetail revision(Long contentId, Long revisionId) {
        Actor actor = policy.actor();
        CommunityContent content = repository.require(CommunityContent.class, contentId);
        ContentRevision revision = repository.require(ContentRevision.class, revisionId);
        if (Objects.equals(revision.getContentId(), contentId) && "submitted".equals(revision.getRevisionStatus())
            && Set.of("wiki", "radar").contains(content.getKind()) && content.getDeletedAt() == null
            && "active".equals(content.getLifecycleStatus())
            && Objects.equals(content.getOwnerOrgId(), actor.person().orgId()) && policy.isModerator(actor)) {
            audit.record(actor.person().id(), "COMMUNITY_SUBMITTED_REVISION_READ", "community_revision",
                revisionId.toString(), actor.person().orgId(), Map.of("contentId", contentId));
            return revision(revision, content);
        }
        policy.readable(contentId, actor);
        policy.requireRevision(content, revision, actor);
        return revision(revision, content);
    }

    public PageResponse<RevisionDetail> history(Long contentId, int page, int size) {
        Actor actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        CommunityPages.check(page, size);
        // History có thể lớn; điều kiện published được lọc ngay ở query trước phân trang.
        return historyPage(content, actor, page, size);
    }

    private PageResponse<RevisionDetail> historyPage(CommunityContent content, Actor actor, int page, int size) {
        boolean editor = policy.canEdit(content, actor);
        return CommunityPages.of(contents.history(content.getContentId(), editor, page * size, size).stream()
            .map(value -> revision(value, content)).toList(), page, size,
            contents.historyCount(content.getContentId(), editor));
    }

    public ContentDetail detail(CommunityContent content, Actor actor, boolean includeDraft) {
        if (!policy.canRead(content, actor)
            || content.getCurrentPublishedRevisionId() == null && !policy.canEdit(content, actor)) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        ContentRevision publishedRow = content.getCurrentPublishedRevisionId() == null ? null
            : repository.require(ContentRevision.class, content.getCurrentPublishedRevisionId());
        ContentRevision draftRow = includeDraft && policy.canEdit(content, actor)
            && content.getCurrentDraftRevisionId() != null
            ? repository.require(ContentRevision.class, content.getCurrentDraftRevisionId()) : null;
        Question question = null;
        boolean current = false;
        if (CommunityCodes.QUESTION.equals(content.getKind())) {
            question = repository.require(Question.class, content.getContentId());
            CommunityContent answer = repository.find(CommunityContent.class, question.getAcceptedAnswerContentId());
            current = answer != null && Objects.equals(answer.getCurrentPublishedRevisionId(),
                question.getAcceptedAnswerRevisionId());
        }
        String status = content.getLifecycleStatus();
        boolean closed = question != null && CommunityCodes.CLOSED.equals(status);
        boolean moderatorClosed = closed && relations.moderatorClosed(content.getContentId());
        List<QuestionLink> links = question == null ? List.of() : management.duplicateLinks(content.getContentId());
        Closure closure = closed ? closure(content, question, !links.isEmpty(), moderatorClosed) : null;
        // Một lần tra tên cho tác giả, người phụ trách, người viết từng bản và người đóng.
        Set<String> people = new HashSet<>();
        Stream.of(content.getAuthorUserId(), content.getMaintainerUserId(),
                publishedRow == null ? null : publishedRow.getCreatedBy(),
                draftRow == null ? null : draftRow.getCreatedBy(),
                closure == null || closure.actor() == null ? null : closure.actor().userId())
            .filter(Objects::nonNull).forEach(people::add);
        Map<String, String> names = identity.fullNames(people);
        RevisionDetail published = publishedRow == null ? null : revision(publishedRow, content, names);
        RevisionDetail draft = draftRow == null ? null : revision(draftRow, content, names);
        if (closure != null && closure.actor() != null) {
            closure = new Closure(closure.action(), closure.reason(),
                new PersonRef(closure.actor().userId(), names.get(closure.actor().userId())), closure.occurredAt());
        }
        boolean active = CommunityCodes.ACTIVE.equals(status);
        try {
            policy.requireInteractive(content, actor);
        } catch (BusinessException exception) {
            active = false;
        }
        boolean author = Objects.equals(content.getAuthorUserId(), actor.person().id());
        boolean editable = policy.canEdit(content, actor);
        boolean owner = policy.isAuthorOrMaintainer(content, actor);
        boolean hasPublished = published != null;
        boolean openOrClosed = Set.of(CommunityCodes.ACTIVE, CommunityCodes.CLOSED).contains(status);
        // Cùng điều kiện mà C17/C136 kiểm tra; UI chỉ hiển thị cờ, không tự suy luận quyền.
        Capabilities capabilities = new Capabilities(active && editable,
            active && owner && !CommunityCodes.REVIEWED_KINDS.contains(content.getKind()),
            active && hasPublished && question != null, active && hasPublished,
            active && hasPublished && !author && CommunityCodes.VOTABLE_KINDS.contains(content.getKind()),
            hasPublished && author && question != null && openOrClosed,
            active && author && !CommunityCodes.ANSWER.equals(content.getKind()), hasPublished,
            owner && question != null && CommunityCodes.ACTIVE.equals(status),
            owner && closed && !moderatorClosed,
            owner && openOrClosed,
            owner && (question == null || relations.otherAnswers(content.getContentId(), actor.person().id()) == 0),
            author && question != null && hasPublished && openOrClosed);
        Object[] totals = relations.voteTotals(content.getContentId());
        var ownVotes = relations.ownVotes(content.getContentId(), actor.person().id());
        ContentRef duplicate = duplicateTarget(links, actor);
        boolean isDuplicate = !links.isEmpty();
        return new ContentDetail(content.getContentId(), content.getKind(), content.getOwnerOrgId(),
            new PersonRef(content.getAuthorUserId(), names.get(content.getAuthorUserId())),
            new PersonRef(content.getMaintainerUserId(), names.get(content.getMaintainerUserId())),
            status, content.getVisibility(), content.getPrimaryGuildId(), content.getRowVersion(),
            published, draft, tagViews(content.getContentId()), capabilities,
            question == null ? null : question.getResolutionStatus(),
            question == null ? null : question.getAcceptedAnswerContentId(),
            question == null ? null : question.getAcceptedAnswerRevisionId(), current,
            question == null ? 0 : relations.visibleAnswerCount(content.getContentId()),
            ((Number) totals[0]).longValue(),
            ownVotes.isEmpty() ? 0 : ownVotes.get(0).getValue(),
            saved(content.getContentId(), actor), subscriptionMode(content.getContentId(), actor),
            isDuplicate, duplicate, closure,
            CommunityCodes.ANSWER.equals(content.getKind())
                ? repository.require(Answer.class, content.getContentId()).getQuestionContentId() : null);
    }

    private boolean saved(Long contentId, Actor actor) {
        return collections.saved(actor.person().id(), contentId);
    }

    private String subscriptionMode(Long contentId, Actor actor) {
        CommunitySubscription subscription = preferences.subscription(actor.person().id(), contentId);
        return subscription == null ? null : subscription.getDeliveryMode();
    }

    /** Chỉ trả câu hỏi gốc khi người gọi đọc được; không lộ tiêu đề bài private qua liên kết trùng lặp. */
    private ContentRef duplicateTarget(List<QuestionLink> links, Actor actor) {
        for (var link : links) {
            var target = repository.find(CommunityContent.class, link.getTargetQuestionId());
            if (target != null && target.getCurrentPublishedRevisionId() != null && policy.canRead(target, actor)) {
                var revision = repository.require(ContentRevision.class, target.getCurrentPublishedRevisionId());
                return new ContentRef(target.getContentId(), target.getKind(), revision.getRevisionId(),
                    revision.getTitle(), "/community/contents/" + target.getContentId());
            }
        }
        return null;
    }

    /**
     * Lần đóng làm câu hỏi đang ở trạng thái đóng: sự kiện mới nhất thật sự đóng nó (close, gắn trùng lặp
     * hoặc điều phối viên đóng), bỏ qua các sự kiện cùng loại không đổi trạng thái đóng. Khi điều phối viên
     * đóng hoặc gắn trùng lặp qua một hồ sơ báo cáo, action là "moderated" và không kèm người làm hay lý
     * do, vì lý do được viết trong bối cảnh hồ sơ báo cáo. Actor chỉ mang userId; tên được tra cùng lúc
     * với các người khác của chi tiết.
     */
    private Closure closure(CommunityContent content, Question question, boolean duplicate, boolean moderatorClosed) {
        for (CommunityOutboxEvent event : relations.closingCandidates(content.getContentId())) {
            JsonNode payload = event.getPayloadJson();
            boolean closing = switch (event.getEventType()) {
                case "content_lifecycle" -> "close".equals(payload.path("action").asText());
                // Sự kiện cũ không có targetQuestionId: tin vào liên kết trùng lặp hiện có.
                case "question_duplicate_changed" -> payload.has("targetQuestionId")
                    ? !payload.get("targetQuestionId").isNull() : duplicate;
                case "content_moderated" -> payload.has("action")
                    ? "close".equals(payload.path("action").asText()) : moderatorClosed;
                default -> false;
            };
            if (!closing) {
                continue;
            }
            Instant at = event.getCreateTime() != null ? event.getCreateTime() : event.getAvailableAt();
            String actorId = event.getActorUserId();
            boolean byOwner = Objects.equals(actorId, content.getAuthorUserId())
                || Objects.equals(actorId, content.getMaintainerUserId());
            if ("content_moderated".equals(event.getEventType()) || !byOwner) {
                return new Closure("moderated", null, null, at);
            }
            String action = "question_duplicate_changed".equals(event.getEventType()) ? "duplicate" : "close";
            return new Closure(action, question.getClosedReason(), new PersonRef(actorId, null), at);
        }
        return new Closure(duplicate ? "duplicate" : "close", null, null, null);
    }

    public RevisionDetail revision(ContentRevision revision, CommunityContent content) {
        String editor = revision.getCreatedBy();
        return revision(revision, content, editor == null ? Map.of() : identity.fullNames(List.of(editor)));
    }

    /** Người viết một bản là người mở bản đó, không phải người cập nhật dòng gần nhất. */
    private RevisionDetail revision(ContentRevision revision, CommunityContent content, Map<String, String> names) {
        RevisionDetail value = mapper.revision(revision, content.getRowVersion(),
            relations.assets(revision.getRevisionId()).stream().map(mapper::asset).toList());
        String editor = revision.getCreatedBy();
        return new RevisionDetail(value.revisionId(), value.contentId(), value.revisionNo(), value.title(),
            value.bodyMarkdown(), value.summary(), value.context(), value.revisionStatus(), value.publishedAt(),
            value.revisionVersion(), value.contentVersion(), value.assets(), value.changeNote(),
            editor == null ? null : new PersonRef(editor, names.get(editor)),
            value.updateTime());
    }

    public List<TagView> tagViews(Long contentId) {
        List<Long> ids = relations.contentTags(contentId).stream().map(ContentTag::getTagId).toList();
        Map<Long, List<TagAlias>> aliases = relations.aliases(ids).stream()
            .collect(Collectors.groupingBy(TagAlias::getTagId));
        var usage = tagRead.usage(ids, policy.actor());
        return relations.tags(ids).stream().map(tag -> mapper.tag(tag,
            aliases.getOrDefault(tag.getTagId(), List.of()).stream()
                .map(TagAlias::getNormalizedAlias).toList(), usage.getOrDefault(tag.getTagId(), 0L))).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeQuestion(Long questionId, String actorId) {
        Question question = repository.require(Question.class, questionId);
        question.setResolutionStatus(question.getAcceptedAnswerContentId() != null ? CommunityCodes.SOLVED
            : relations.visibleAnswerCount(questionId) > 0 ? CommunityCodes.ANSWERED : CommunityCodes.UNANSWERED);
        repository.save(question, actorId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void event(CommunityContent content, String type, String actorId) {
        event(content, type, actorId, JsonNodeFactory.instance.objectNode());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void event(CommunityContent content, String type, String actorId,
                      JsonNode eventPayload) {
        CommunityOutboxEvent event = new CommunityOutboxEvent();
        event.setOwnerOrgId(content.getOwnerOrgId());
        event.setEventType(type);
        event.setActorUserId(actorId);
        event.setContentId(content.getContentId());
        event.setPayloadJson(eventPayload);
        event.setDedupKey(UUID.randomUUID().toString());
        event.setEventStatus("pending");
        event.setAvailableAt(Instant.now());
        event.setAttemptCount(0);
        repository.save(event, actorId);
    }

    private ContentRevision editableDraft(CommunityContent content) {
        ContentRevision draft = repository.require(ContentRevision.class, content.getCurrentDraftRevisionId());
        if (!CommunityCodes.DRAFT.equals(draft.getRevisionStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        return draft;
    }

    private void replaceTags(CommunityContent content, List<Long> requested, Actor actor) {
        List<Long> ids = requested == null ? List.of() : requested;
        if (CommunityCodes.QUESTION.equals(content.getKind()) && content.getCurrentPublishedRevisionId() != null
            && ids.isEmpty()) {
            throw new BusinessException(CommunityResponseCode.NOT_PUBLISHABLE);
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(CommunityResponseCode.DUPLICATE);
        }
        for (Long id : ids) {
            CommunityTag tag = repository.require(CommunityTag.class, id);
            if (!Objects.equals(tag.getOwnerOrgId(), actor.person().orgId())
                || !CommunityCodes.ACTIVE.equals(tag.getStatus())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        }
        relations.contentTags(content.getContentId()).forEach(repository::remove);
        for (Long id : ids) {
            ContentTag tag = new ContentTag();
            tag.setContentId(content.getContentId());
            tag.setTagId(id);
            repository.save(tag, actor.person().id());
        }
    }

    private void replaceAssets(CommunityContent content, ContentRevision revision, List<AssetInput> requested,
                               Actor actor) {
        var previousIds = relations.assets(revision.getRevisionId()).stream().map(RevisionAsset::getAssetId).distinct()
            .toList();
        List<AssetInput> inputs = requested == null ? List.of() : requested;
        if (inputs.stream().filter(input -> "cover".equals(input.usage())).count() > 1) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        Set<Long> ids = new HashSet<>();
        for (AssetInput input : inputs) {
            if (!ids.add(input.assetId())) {
                throw new BusinessException(CommunityResponseCode.DUPLICATE);
            }
            var asset = repository.require(CommunityAsset.class, input.assetId());
            if ("cover".equals(input.usage())
                && (!"image".equals(asset.getMediaType()) || asset.getMediaCheckedAt() == null)) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            assetUsable(content, asset, actor);
        }
        relations.assets(revision.getRevisionId()).forEach(repository::remove);
        for (AssetInput input : inputs) {
            RevisionAsset link = new RevisionAsset();
            link.setRevisionId(revision.getRevisionId());
            link.setAssetId(input.assetId());
            link.setUsage(input.usage());
            link.setCaption(input.caption());
            link.setAltText(input.altText());
            link.setSortNo(input.sortNo());
            repository.save(link, actor.person().id());
        }
        repository.flush();
        for (Long id : previousIds) {
            if (!media.hasReferences(id)) {
                var detached = repository.require(CommunityAsset.class, id);
                detached.setCleanupAfter(Instant.now().plus(mediaProperties.getOrphanRetention()));
                repository.save(detached, actor.person().id());
            }
        }
    }

    private void assetUsable(CommunityContent content, CommunityAsset asset, Actor actor) {
        // Không cho gắn asset người khác bằng ID để vô tình công bố file private.
        if (!Objects.equals(asset.getOwnerOrgId(), content.getOwnerOrgId()) || asset.getDeletedAt() != null
            || !"ready".equals(asset.getStatus()) || "deleting".equals(asset.getCleanupStatus())
            || !Objects.equals(asset.getUploadedBy(), actor.person().id()) && !(policy.isAuthorOrMaintainer(content,
                actor)
                && media.usages(asset.getAssetId()).stream().anyMatch(usage -> Objects.equals(
                    repository.require(ContentRevision.class, usage.getRevisionId()).getContentId(),
                        content.getContentId())))) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        asset.setCleanupAfter(null);
        asset.setCleanupStatus("none");
        repository.save(asset, actor.person().id());
    }

    private void visibility(String visibility, Long guildId, Actor actor) {
        if (!CommunityCodes.VISIBILITIES.contains(visibility == null ? "" : visibility)
            || CommunityCodes.GUILD.equals(visibility) != (guildId != null)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        if (guildId != null) {
            sameOrgGuild(guildId, actor);
            if (!policy.member(guildId, actor)) {
                throw new AccessDeniedException("Guild membership required");
            }
        }
    }

    private void sameOrgGuild(Long guildId, Actor actor) {
        Guild guild = repository.require(Guild.class, guildId);
        if (!Objects.equals(guild.getOwnerOrgId(), actor.person().orgId())
            || !CommunityCodes.ACTIVE.equals(guild.getStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
    }

    public void requirePublished(CommunityContent content) {
        if (content.getCurrentPublishedRevisionId() == null) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
    }

    public void requireKind(CommunityContent content, String kind) {
        if (!kind.equals(content.getKind())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
    }

    private void requireNonAnswer(CommunityContent content) {
        if (CommunityCodes.ANSWER.equals(content.getKind())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
    }
}
