package vn.com.coreacademy.community.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.CommunityManagementDtos.DuplicateQuestion;
import vn.com.coreacademy.community.dto.CommunityManagementDtos.Maintainer;
import vn.com.coreacademy.community.dto.CommunityManagementDtos.MergeTag;
import vn.com.coreacademy.community.dto.CommunityManagementDtos.PinView;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.TagView;
import vn.com.coreacademy.community.entity.CommunityTag;
import vn.com.coreacademy.community.entity.ContentGuild;
import vn.com.coreacademy.community.entity.Question;
import vn.com.coreacademy.community.entity.QuestionLink;
import vn.com.coreacademy.community.entity.TagAlias;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityManagementRepository;
import vn.com.coreacademy.community.repository.CommunityRelations;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;

@Service
@RequiredArgsConstructor
public class CommunityManagementService {
    private final CommunityRepository repository;
    private final CommunityManagementRepository management;
    private final CommunityRelations relations;
    private final CommunityPolicy policy;
    private final CommunityContentService contents;
    private final CommunityReportService reports;
    private final CommunityTagService tags;
    private final CommunityGuildService guilds;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    @Transactional
    public TagView merge(Long sourceId, MergeTag request) {
        writeLock.acquire();
        var actor = policy.actor();
        policy.requireModerator(actor);
        var source = repository.require(CommunityTag.class, sourceId);
        var target = repository.require(CommunityTag.class, request.targetTagId());
        policy.version(source, request.expectedVersion());
        if (!Objects.equals(source.getOwnerOrgId(), actor.person().orgId())
            || !Objects.equals(target.getOwnerOrgId(), actor.person().orgId())
            || sourceId.equals(target.getTagId()) || source.getMergedIntoTagId() != null
            || !"active".equals(target.getStatus()) || target.getMergedIntoTagId() != null) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        var aliases = relations.aliases(List.of(sourceId, target.getTagId()));
        management.mergeTag(sourceId, target.getTagId(), actor.person().id());
        if (aliases.stream().noneMatch(alias -> alias.getNormalizedAlias().equals(source.getSlug()))) {
            TagAlias alias = new TagAlias();
            alias.setTagId(target.getTagId());
            alias.setNormalizedAlias(source.getSlug());
            repository.save(alias, actor.person().id());
        }
        source.setStatus("merged");
        source.setMergedIntoTagId(target.getTagId());
        repository.save(source, actor.person().id());
        audit.record(actor.person().id(), "COMMUNITY_TAG_MERGED", "community_tag", sourceId.toString(),
            actor.person().orgId(), Map.of("targetTagId", target.getTagId(), "reason", request.reason()));
        repository.flush();
        return tags.view(source);
    }

    @Transactional
    public ContentDetail maintainer(Long contentId, Maintainer request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        policy.requireAuthor(content, actor);
        policy.requireInteractive(content, actor);
        policy.version(content, request.expectedContentVersion());
        if (!Objects.equals(identity.activePerson(request.maintainerUserId()).orgId(), content.getOwnerOrgId())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        content.setMaintainerUserId(request.maintainerUserId());
        repository.save(content, actor.person().id());
        contents.event(content, "maintainer_changed", actor.person().id());
        repository.flush();
        return contents.detail(content, actor, false);
    }

    @Transactional
    public ContentDetail duplicate(Long questionId, DuplicateQuestion request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = policy.readable(questionId, actor);
        contents.requireKind(content, "question");
        contents.requirePublished(content);
        policy.version(content, request.expectedContentVersion());
        if (!Objects.equals(content.getAuthorUserId(), actor.person().id())) {
            if (request.reportId() == null || !Objects.equals(reports.assigned(request.reportId(), actor)
                .getContentId(),
                questionId)) {
                throw new AccessDeniedException("Question author or assigned moderator required");
            }
        }
        if (!Set.of("active", "closed").contains(content.getLifecycleStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        if (request.targetQuestionId() != null) {
            var target = policy.readable(request.targetQuestionId(), actor);
            contents.requireKind(target, "question");
            contents.requirePublished(target);
            if (management.duplicateCycle(questionId, target.getContentId())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        }
        management.duplicateLinks(questionId).forEach(repository::remove);
        if (request.targetQuestionId() != null) {
            QuestionLink link = new QuestionLink();
            link.setSourceQuestionId(questionId);
            link.setTargetQuestionId(request.targetQuestionId());
            link.setRelationType("duplicate");
            link.setReason(request.reason());
            repository.save(link, actor.person().id());
            content.setLifecycleStatus("closed");
            var question = repository.require(Question.class, questionId);
            question.setClosedReason(request.reason());
            repository.save(question, actor.person().id());
        }
        repository.incrementVersion(content, actor.person().id());
        contents.event(content, "question_duplicate_changed", actor.person().id());
        repository.flush();
        return contents.detail(content, actor, false);
    }

    @Transactional
    public PinView pin(Long guildId, Long contentId, boolean pinned) {
        writeLock.acquire();
        var actor = policy.actor();
        guilds.requireManager(guildId, actor);
        var links = management.pins(guildId, contentId);
        if (pinned) {
            var content = policy.readable(contentId, actor);
            contents.requirePublished(content);
            ContentGuild link = links.isEmpty() ? new ContentGuild() : links.get(0);
            link.setGuildId(guildId);
            link.setContentId(contentId);
            link.setIsPinned(true);
            repository.save(link, actor.person().id());
        } else if (!links.isEmpty()) {
            links.get(0).setIsPinned(false);
            repository.save(links.get(0), actor.person().id());
        }
        return new PinView(guildId, contentId, pinned);
    }
}
