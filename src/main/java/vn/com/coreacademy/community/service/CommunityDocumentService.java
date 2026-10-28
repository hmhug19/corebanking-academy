package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.ContentRequests.PublishRequest;
import vn.com.coreacademy.community.dto.ContentViews.Capabilities;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.ContentViews.RevisionDetail;
import vn.com.coreacademy.community.dto.DocumentDtos.ApprovalResult;
import vn.com.coreacademy.community.dto.DocumentDtos.ApprovalView;
import vn.com.coreacademy.community.dto.DocumentDtos.MaintenanceRequest;
import vn.com.coreacademy.community.dto.DocumentDtos.MaintenanceReviewView;
import vn.com.coreacademy.community.dto.DocumentDtos.RadarCard;
import vn.com.coreacademy.community.dto.DocumentDtos.ReviewSchedule;
import vn.com.coreacademy.community.dto.DocumentDtos.RevisionCard;
import vn.com.coreacademy.community.dto.DocumentDtos.RevisionDecision;
import vn.com.coreacademy.community.dto.DocumentDtos.WikiLocation;
import vn.com.coreacademy.community.dto.DocumentDtos.WikiPageCard;
import vn.com.coreacademy.community.dto.DocumentDtos.WikiPageView;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateRadar;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateWiki;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.MaintenanceReview;
import vn.com.coreacademy.community.entity.RadarEntry;
import vn.com.coreacademy.community.entity.RevisionApproval;
import vn.com.coreacademy.community.entity.WikiPage;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityAccessRepository;
import vn.com.coreacademy.community.repository.CommunityDocumentRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.community.repository.ContentRepository.DocumentFilter;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityDocumentService {
    private final CommunityRepository repository;
    private final CommunityDocumentRepository documents;
    private final CommunityAccessRepository access;
    private final CommunityContentService contents;
    private final CommunityPolicy policy;
    private final CommunityGuildService guilds;
    private final CommunityPayload payload;
    private final LearningIdentityService identity;
    private final IdentityAuditService audit;
    private final IdentityWriteLock writeLock;

    public PageResponse<WikiPageCard> wiki(Long guildId, Long parentPageId, String q, int page, int size) {
        guilds.requireFeed(guildId);
        var actor = policy.actor();
        if (parentPageId != null) {
            wikiParent(guildId, null, parentPageId, actor);
        }
        var rows = contents.list(search("wiki", guildId, q, new DocumentFilter(parentPageId, true,
            null, null, null, null, null, null, null, null, false), page, size));
        return CommunityPages.map(rows, card -> {
            WikiPage wiki = repository.require(WikiPage.class, card.contentId());
            var revision = repository.require(ContentRevision.class, card.publishedRevisionId());
            return new WikiPageCard(card, wiki.getGuildId(), wiki.getParentPageContentId(), wiki.getSlug(),
                wiki.getSortNo(), revision.getContextJson().path("wiki").path("documentClass").asText(),
                wiki.getReviewDueAt(), documents.obsolete(revision.getRevisionId()));
        });
    }

    @Transactional
    public WikiPageView createWiki(Long guildId, CreateWiki request) {
        writeLock.acquire();
        var actor = policy.actor();
        requireCreate(guildId, actor);
        String hash = payload.hash("wiki:" + guildId, request);
        var content = contents.create("wiki", null, request.content(guildId), hash);
        WikiPage wiki = repository.find(WikiPage.class, content.contentId());
        if (wiki == null) {
            validateLocation(guildId, content.contentId(), request.parentPageId(), request.slug(), actor);
            wiki = new WikiPage();
            wiki.setContentId(content.contentId());
            wiki.setGuildId(guildId);
            wiki.setParentPageContentId(request.parentPageId());
            wiki.setSlug(request.slug());
            wiki.setSortNo(request.sortNo());
            repository.save(wiki, actor.person().id());
            repository.flush();
        }
        return wikiView(wiki, actor);
    }

    @Transactional
    public ContentDetail createRadar(Long guildId, CreateRadar request) {
        writeLock.acquire();
        var actor = policy.actor();
        requireCreate(guildId, actor);
        var detail = contents.create("radar", null, request.content(guildId), payload.hash("radar:" + guildId,
            request));
        if (repository.find(RadarEntry.class, detail.contentId()) == null) {
            RadarEntry radar = new RadarEntry();
            radar.setContentId(detail.contentId());
            radar.setGuildId(guildId);
            repository.save(radar, actor.person().id());
        }
        return contents.detail(detail.contentId(), true);
    }

    public PageResponse<RadarCard> radar(Long guildId, String stage, String q, int page, int size) {
        guilds.requireFeed(guildId);
        return CommunityPages.map(contents.list(search("radar", guildId, q,
            new DocumentFilter(null, false, stage, null, null, null, null, null, null, null, false), page, size)),
                card -> {
                RadarEntry radar = repository.require(RadarEntry.class, card.contentId());
                JsonNode metadata = repository.require(ContentRevision.class, card.publishedRevisionId())
                    .getContextJson().path("radar");
                return new RadarCard(card, guildId, metadata.path("technologyName").asText(),
                    metadata.has("technologyVersion") ? metadata.get("technologyVersion").asText() : null,
                    metadata.path("stage").asText(), metadata.path("approvalScope").asText(),
                    metadata.has("effectiveFrom") ? LocalDate.parse(metadata.get("effectiveFrom").asText()) : null,
                    radar.getReviewDueAt());
            });
    }

    @Transactional
    public WikiPageView location(Long contentId, WikiLocation request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        contents.requireKind(content, "wiki");
        policy.requirePublisher(content, actor);
        policy.version(content, request.expectedContentVersion());
        WikiPage wiki = repository.require(WikiPage.class, contentId);
        validateLocation(wiki.getGuildId(), contentId, request.parentPageId(), request.slug(), actor);
        wiki.setParentPageContentId(request.parentPageId());
        wiki.setSlug(request.slug());
        wiki.setSortNo(request.sortNo());
        repository.save(wiki, actor.person().id());
        repository.incrementVersion(content, actor.person().id());
        repository.flush();
        return wikiView(wiki, actor);
    }

    @Transactional
    public RevisionDetail submit(Long contentId, PublishRequest request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityContent content = policy.readable(contentId, actor);
        requireReviewedKind(content);
        policy.requirePublisher(content, actor);
        policy.version(content, request.expectedContentVersion());
        var revision = repository.require(ContentRevision.class, content.getCurrentDraftRevisionId());
        policy.version(revision, request.expectedRevisionVersion());
        if (!"draft".equals(revision.getRevisionStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        contents.validatePublish(content, revision, actor);
        revision.setRevisionStatus("submitted");
        repository.save(revision, actor.person().id());
        repository.incrementVersion(content, actor.person().id());
        contents.event(content, "revision_submitted", actor.person().id());
        repository.flush();
        return contents.revision(revision, content);
    }

    @Transactional
    public ApprovalResult decide(Long revisionId, RevisionDecision request) {
        writeLock.acquire();
        var actor = policy.actor();
        policy.requireModerator(actor);
        var revision = repository.require(ContentRevision.class, revisionId);
        var content = repository.require(CommunityContent.class, revision.getContentId());
        requireReviewedKind(content);
        if (!Objects.equals(content.getOwnerOrgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        policy.version(content, request.expectedContentVersion());
        policy.version(revision, request.expectedRevisionVersion());
        if (!"submitted".equals(revision.getRevisionStatus()) || !"active".equals(content.getLifecycleStatus())
            || content.getDeletedAt() != null || !Objects.equals(content.getCurrentDraftRevisionId(), revisionId)) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        if (contributor(content, revision, actor.person().id())) {
            throw BusinessException.validation("decision", "SELF_APPROVAL",
                "Không được tự duyệt revision đã đóng góp.");
        }
        policy.requireGoverningGuildAvailable(content);
        if (!"approved".equals(request.decision()) && (request.note() == null || request.note().isBlank())) {
            throw BusinessException.validation("note", "REQUIRED", "Hãy ghi rõ lý do yêu cầu sửa hoặc từ chối.");
        }
        if ("approved".equals(request.decision())) {
            contents.validatePublish(content, revision, actor);
            contents.publishRevision(content, revision, actor);
        } else {
            revision.setRevisionStatus("rejected");
            repository.save(revision, actor.person().id());
            repository.incrementVersion(content, actor.person().id());
        }
        RevisionApproval approval = new RevisionApproval();
        approval.setRevisionId(revisionId);
        approval.setReviewerUserId(actor.person().id());
        approval.setDecision(request.decision());
        approval.setNote(request.note());
        approval.setDecidedAt(Instant.now());
        repository.save(approval, actor.person().id());
        audit.record(actor.person().id(), "COMMUNITY_REVISION_DECIDED", "community_revision", revisionId.toString(),
            actor.person().orgId(), Map.of("decision", request.decision()));
        contents.event(content, "revision_decided", actor.person().id());
        repository.flush();
        return new ApprovalResult(approval(approval, content, revision), decisionContent(content, revision, actor));
    }

    @Transactional
    public PageResponse<ApprovalView> approvals(Long revisionId, int page, int size) {
        var revision = repository.require(ContentRevision.class, revisionId);
        contents.revision(revision.getContentId(), revisionId);
        CommunityPages.check(page, size);
        var content = repository.require(CommunityContent.class, revision.getContentId());
        return PageResponse.from(documents.approvals(revisionId, page, size).map(value -> approval(value, content,
            revision)));
    }

    @Transactional
    public MaintenanceReviewView maintain(Long contentId, MaintenanceRequest request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        if (!policy.isAuthorOrMaintainer(content, actor)) {
            policy.requireModerator(actor);
        }
        policy.version(content, request.expectedContentVersion());
        if (!Objects.equals(content.getCurrentPublishedRevisionId(), request.revisionId())
            || request.nextReviewDueAt() != null && request.nextReviewDueAt().isBefore(LocalDate.now())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        MaintenanceReview review = new MaintenanceReview();
        review.setContentId(contentId);
        review.setRevisionId(request.revisionId());
        review.setReviewerUserId(actor.person().id());
        review.setOutcome(request.outcome());
        review.setNote(request.note());
        review.setReviewedAt(Instant.now());
        review.setNextReviewDueAt(request.nextReviewDueAt());
        repository.save(review, actor.person().id());
        if ("wiki".equals(content.getKind())) {
            var wiki = repository.require(WikiPage.class, contentId);
            wiki.setReviewDueAt(request.nextReviewDueAt());
            repository.save(wiki, actor.person().id());
        } else if ("radar".equals(content.getKind())) {
            var radar = repository.require(RadarEntry.class, contentId);
            radar.setReviewDueAt(request.nextReviewDueAt());
            repository.save(radar, actor.person().id());
        }
        repository.incrementVersion(content, actor.person().id());
        contents.event(content, "maintenance_reviewed", actor.person().id());
        repository.flush();
        return maintenance(review, content);
    }

    public PageResponse<MaintenanceReviewView> maintenance(Long contentId, int page, int size) {
        var content = policy.readable(contentId, policy.actor());
        contents.requirePublished(content);
        CommunityPages.check(page, size);
        return PageResponse.from(documents.maintenance(contentId, page, size).map(value -> maintenance(value,
            content)));
    }

    public PageResponse<ContentCard> due(Long guildId, String kind, LocalDate dueBefore, int page, int size) {
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        return contents.list(search(kind, guildId, null, new DocumentFilter(null, false, null,
            dueBefore == null ? LocalDate.now() : dueBefore, null, null, null, null, null, null, false), page, size));
    }

    @Transactional
    public WikiPageView schedule(Long contentId, ReviewSchedule request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        contents.requireKind(content, "wiki");
        policy.requirePublisher(content, actor);
        policy.version(content, request.expectedContentVersion());
        var wiki = repository.require(WikiPage.class, contentId);
        wiki.setReviewDueAt(request.reviewDueAt());
        repository.save(wiki, actor.person().id());
        repository.incrementVersion(content, actor.person().id());
        repository.flush();
        return wikiView(wiki, actor);
    }

    @Transactional
    public PageResponse<RevisionCard> queue(String kind, Long guildId, int page, int size) {
        var actor = policy.actor();
        policy.requireModerator(actor);
        CommunityPages.check(page, size);
        if (kind != null && !Set.of("wiki", "radar").contains(kind)) {
            throw BusinessException.validation("kind", "INVALID_VALUE", "Hàng đợi chỉ nhận Wiki/Radar.");
        }
        return PageResponse.from(documents.queue(actor, kind, guildId, page, size).map(revision -> {
            var content = repository.require(CommunityContent.class, revision.getContentId());
            audit.record(actor.person().id(), "COMMUNITY_REVISION_QUEUE_READ", "community_revision",
                revision.getRevisionId().toString(), actor.person().orgId(), Map.of("contentId",
                    content.getContentId()));
            return new RevisionCard(revision.getRevisionId(), revision.getContentId(), revision.getRevisionNo(),
                revision.getRowVersion(), revision.getTitle(), revision.getRevisionStatus(), revision.getPublishedAt(),
                revision.getChangeNote(), !contributor(content, revision, actor.person().id()),
                Map.of("canApprove", !contributor(content, revision, actor.person().id())));
        }));
    }

    private ContentRepository.Search search(String kind, Long guildId, String q, DocumentFilter filter,
                                            int page, int size) {
        return new ContentRepository.Search(kind, q, null, null, null, false, null, null,
            page, size, "publishedAt", guildId, filter);
    }

    private void requireCreate(Long guildId, Actor actor) {
        var guild = guilds.readable(guildId, actor);
        if (!policy.member(guildId, actor) || !"active".equals(guild.getStatus())
            || !Objects.equals(identity.activePerson(guild.getOwnerUserId()).orgId(), guild.getOwnerOrgId())) {
            throw new AccessDeniedException("Writable Guild membership required");
        }
    }

    private void validateLocation(Long guildId, Long id, Long parentId, String slug, Actor actor) {
        if (documents.slugUsed(guildId, slug, id)) {
            throw new BusinessException(CommunityResponseCode.DUPLICATE);
        }
        if (parentId != null) {
            wikiParent(guildId, id, parentId, actor);
        }
    }

    private void wikiParent(Long guildId, Long id, Long parentId, Actor actor) {
        var parent = policy.readable(parentId, actor);
        contents.requireKind(parent, "wiki");
        var wiki = repository.require(WikiPage.class, parentId);
        if (!Objects.equals(wiki.getGuildId(), guildId) || id != null && documents.parentCycle(id, parentId)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
    }

    private void requireReviewedKind(CommunityContent content) {
        if (!Set.of("wiki", "radar").contains(content.getKind())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
    }

    private boolean contributor(CommunityContent content, ContentRevision revision, String actor) {
        return Objects.equals(content.getAuthorUserId(), actor) || Objects.equals(content.getMaintainerUserId(), actor)
            || Objects.equals(revision.getCreatedBy(), actor) || audit.hasAction(actor, "community_revision",
                revision.getRevisionId().toString(), "COMMUNITY_REVISION_EDITED");
    }

    private PersonRef person(String id) {
        return new PersonRef(id, identity.fullNames(List.of(id)).get(id));
    }

    private ApprovalView approval(RevisionApproval value, CommunityContent content, ContentRevision revision) {
        return new ApprovalView(value.getApprovalId(), value.getRevisionId(), person(value.getReviewerUserId()),
            value.getDecision(), value.getNote(), value.getDecidedAt(), content.getRowVersion(),
                revision.getRowVersion(),
            value.getRowVersion(), value.getCreateTime(), value.getUpdateTime());
    }

    private MaintenanceReviewView maintenance(MaintenanceReview value, CommunityContent content) {
        return new MaintenanceReviewView(value.getMaintenanceReviewId(), value.getContentId(), value.getRevisionId(),
            person(value.getReviewerUserId()), value.getOutcome(), value.getNote(), value.getReviewedAt(),
            value.getNextReviewDueAt(), content.getRowVersion(), value.getRowVersion(), value.getCreateTime(),
            value.getUpdateTime());
    }

    private WikiPageView wikiView(WikiPage wiki, Actor actor) {
        var content = policy.readable(wiki.getContentId(), actor);
        return new WikiPageView(contents.detail(content, actor, true), wiki.getGuildId(), wiki.getParentPageContentId(),
            wiki.getSlug(), wiki.getSortNo(), wiki.getReviewDueAt(), content.getCurrentPublishedRevisionId() != null
                && documents.obsolete(content.getCurrentPublishedRevisionId()));
    }

    private ContentDetail decisionContent(CommunityContent content, ContentRevision reviewed, Actor actor) {
        if (policy.canRead(content, actor)) {
            return contents.detail(content, actor, true);
        }
        // Quyền duyệt chỉ mở đúng bản vừa xử lý trong response này, không mở published/draft khác của bài private.
        RevisionDetail revision = contents.revision(reviewed, content);
        return new ContentDetail(content.getContentId(), content.getKind(), content.getOwnerOrgId(),
            person(content.getAuthorUserId()), person(content.getMaintainerUserId()), content.getLifecycleStatus(),
            content.getVisibility(), content.getPrimaryGuildId(), content.getRowVersion(),
            "published".equals(reviewed.getRevisionStatus()) ? revision : null,
            "published".equals(reviewed.getRevisionStatus()) ? null : revision,
                contents.tagViews(content.getContentId()),
            new Capabilities(false, false, false, false, false, false, false, false), null, null, null, false, 0, 0, 0);
    }
}
