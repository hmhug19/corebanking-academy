package vn.com.coreacademy.community.service;

import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.entity.Answer;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunityRecord;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.Guild;
import vn.com.coreacademy.community.entity.KnowledgeRequest;
import vn.com.coreacademy.community.entity.RadarEntry;
import vn.com.coreacademy.community.entity.ReviewProposal;
import vn.com.coreacademy.community.entity.WikiPage;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityAccessRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.security.SecurityContextUtils;

/** Không có quyền đọc riêng tư ngầm từ role quản trị; answer luôn kiểm tra ACL câu hỏi trước. */
@Service
@RequiredArgsConstructor
public class CommunityPolicy {
    private final LearningIdentityService identity;
    private final CommunityRepository repository;
    private final CommunityAccessRepository access;

    public Actor actor() {
        Actor actor = identity.actor(SecurityContextUtils.getUserId());
        identity.activeOrg(actor.person().orgId());
        return actor;
    }

    public CommunityContent readable(Long id, Actor actor) {
        CommunityContent content = repository.require(CommunityContent.class, id);
        if (!canRead(content, actor)) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return content;
    }

    public boolean canRead(CommunityContent content, Actor actor) {
        if (content.getDeletedAt() != null || CommunityCodes.HIDDEN.equals(content.getLifecycleStatus())
            || !Objects.equals(content.getOwnerOrgId(), actor.person().orgId())) {
            return false;
        }
        if (CommunityCodes.ANSWER.equals(content.getKind())) {
            Answer answer = repository.require(Answer.class, content.getContentId());
            CommunityContent parent = repository.require(CommunityContent.class, answer.getQuestionContentId());
            return canRead(parent, actor);
        }
        return switch (content.getVisibility()) {
            case CommunityCodes.ORGANIZATION -> true;
            case CommunityCodes.GUILD -> member(content.getPrimaryGuildId(), actor);
            case CommunityCodes.RESTRICTED -> isAuthorOrMaintainer(content, actor)
                || grant(content, actor, false);
            default -> false;
        };
    }

    public boolean isAuthorOrMaintainer(CommunityContent content, Actor actor) {
        return Objects.equals(content.getAuthorUserId(), actor.person().id())
            || Objects.equals(content.getMaintainerUserId(), actor.person().id());
    }

    public boolean canEdit(CommunityContent content, Actor actor) {
        return canRead(content, actor) && (isAuthorOrMaintainer(content, actor) || grant(content, actor, true));
    }

    public void requireEdit(CommunityContent content, Actor actor) {
        if (!canEdit(content, actor)) {
            throw new AccessDeniedException("Community editor required");
        }
        requireInteractive(content, actor);
    }

    public void requirePublisher(CommunityContent content, Actor actor) {
        requireEdit(content, actor);
        if (!isAuthorOrMaintainer(content, actor)) {
            throw new AccessDeniedException("Community publisher required");
        }
    }

    public void requireAuthor(CommunityContent content, Actor actor) {
        if (!canRead(content, actor) || !Objects.equals(content.getAuthorUserId(), actor.person().id())) {
            throw new AccessDeniedException("Community author required");
        }
    }

    public void requireRevision(CommunityContent content, ContentRevision revision, Actor actor) {
        if (!Objects.equals(revision.getContentId(), content.getContentId()) || revision.getDeletedAt() != null
            || (!CommunityCodes.PUBLISHED.equals(revision.getRevisionStatus())
                && !CommunityCodes.SUPERSEDED.equals(revision.getRevisionStatus()) && !canEdit(content, actor))) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
    }

    public void requireInteractive(CommunityContent content, Actor actor) {
        requireOperational(content, actor);
        if ("architecture_review".equals(content.getKind())) {
            var proposal = repository.require(ReviewProposal.class, content.getContentId());
            if (Set.of("concluded", "closed").contains(proposal.getReviewStatus())) {
                throw new BusinessException(CommunityResponseCode.INVALID_STATE);
            }
        }
        if ("knowledge_request".equals(content.getKind())
            && "closed".equals(repository.require(KnowledgeRequest.class, content.getContentId()).getRequestStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
    }

    public void requireOperational(CommunityContent content, Actor actor) {
        if (!canRead(content, actor) || !CommunityCodes.ACTIVE.equals(content.getLifecycleStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        if (CommunityCodes.ANSWER.equals(content.getKind())) {
            Answer answer = repository.require(Answer.class, content.getContentId());
            requireInteractive(repository.require(CommunityContent.class, answer.getQuestionContentId()), actor);
            return;
        }
        requireGoverningGuildAvailable(content);
    }

    public void requireGoverningGuildAvailable(CommunityContent content) {
        Long guildId = content.getPrimaryGuildId();
        if ("wiki".equals(content.getKind())) {
            guildId = repository.require(WikiPage.class, content.getContentId()).getGuildId();
        } else if ("radar".equals(content.getKind())) {
            guildId = repository.require(RadarEntry.class, content.getContentId()).getGuildId();
        }
        if (guildId != null) {
            Guild guild = repository.require(Guild.class, guildId);
            if (!CommunityCodes.ACTIVE.equals(guild.getStatus())) {
                throw new BusinessException(CommunityResponseCode.INVALID_STATE);
            }
            // Chủ Guild mất hiệu lực làm Guild chỉ đọc; không cản thao tác khóa/chuyển org của Identity.
            var owner = identity.activePerson(guild.getOwnerUserId());
            if (!Objects.equals(owner.orgId(), guild.getOwnerOrgId())) {
                throw new BusinessException(CommunityResponseCode.INVALID_STATE);
            }
        }
    }

    public boolean member(Long guildId, Actor actor) {
        return access.member(guildId, actor.person().id(), actor.person().orgId());
    }

    public void requireModerator(Actor actor) {
        if (!access.moderator(actor.person().id(), actor.person().orgId())) {
            throw new AccessDeniedException("Community moderator required");
        }
    }

    public boolean isModerator(Actor actor) {
        return access.moderator(actor.person().id(), actor.person().orgId());
    }

    public void version(CommunityRecord record, Long expected) {
        if (expected == null || !Objects.equals(record.getRowVersion(), expected)) {
            throw new BusinessException(CommunityResponseCode.CONFLICT);
        }
    }

    private boolean grant(CommunityContent content, Actor actor, boolean editor) {
        return access.grant(content.getContentId(), actor.person().id(), actor.person().orgId(), editor);
    }
}
