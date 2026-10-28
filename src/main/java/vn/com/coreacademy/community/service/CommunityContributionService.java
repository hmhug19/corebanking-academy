package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.ContributionDtos.BadgeAwardView;
import vn.com.coreacademy.community.dto.ContributionDtos.BadgeView;
import vn.com.coreacademy.community.dto.ContributionDtos.ContributionView;
import vn.com.coreacademy.community.dto.ContributionDtos.Contributions;
import vn.com.coreacademy.community.dto.ContributionDtos.ContributorRank;
import vn.com.coreacademy.community.dto.ContributionDtos.CreateBadge;
import vn.com.coreacademy.community.dto.ContributionDtos.RetireBadge;
import vn.com.coreacademy.community.entity.BadgeAward;
import vn.com.coreacademy.community.entity.CommunityBadge;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.ContributionEvent;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.mapper.CommunityBadgeMapper;
import vn.com.coreacademy.community.repository.CommunityBadgeRepository;
import vn.com.coreacademy.community.repository.CommunityContributionRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityContributionService {
    private final CommunityRepository repository;
    private final CommunityBadgeRepository badges;
    private final CommunityContributionRepository contributions;
    private final CommunityPolicy policy;
    private final CommunityGuildService guilds;
    private final CommunityBadgeMapper mapper;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;
    private final ObjectMapper json;

    public Contributions personal(Instant from, Instant to, int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        var period = CommunityAnalyticsService.period(from, to);
        var ledger = badges.ledger(actor, period.from(), period.to(), page, size).map(this::contribution);
        var metrics = contributions.metrics(actor.person().orgId(), actor.person().id(), period.from(), period.to(),
            null, 0, 1);
        return new Contributions(PageResponse.from(ledger), metrics.isEmpty() ? 0 : ((Number) metrics.get(0)[1])
            .longValue());
    }

    public PageResponse<ContributorRank> leaderboard(Instant from, Instant to, Long guildId, int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        var period = CommunityAnalyticsService.period(from, to);
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        var rows = contributions.metrics(actor.person().orgId(), null, period.from(), period.to(), guildId,
            page * size, size);
        var names = identity.fullNames(rows.stream().map(row -> row[0].toString()).toList());
        List<ContributorRank> ranks = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            String user = row[0].toString();
            ranks.add(new ContributorRank((long) page * size + index + 1, new PersonRef(user, names.get(user)),
                number(row[1]), number(row[2]), number(row[3]), number(row[4]), allAwards(user)));
        }
        return CommunityPages.of(ranks, page, size,
            contributions.metricUsers(actor.person().orgId(), period.from(), period.to(), guildId));
    }

    public PageResponse<BadgeView> definitions(String q, String status, int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        if (q != null && q.length() > 300 || status != null && !Set.of("active", "retired").contains(status)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        return PageResponse.from(badges.definitions(actor.person().orgId(), q, status, page, size).map(mapper::badge));
    }

    @Transactional
    public BadgeView create(CreateBadge request) {
        writeLock.acquire();
        var actor = policy.actor();
        policy.requireModerator(actor);
        if (badges.codeUsed(actor.person().orgId(), request.code(), request.ruleVersion())) {
            throw new BusinessException(CommunityResponseCode.DUPLICATE);
        }
        CommunityBadge badge = new CommunityBadge();
        badge.setOwnerOrgId(actor.person().orgId());
        badge.setCode(request.code());
        badge.setName(request.name());
        badge.setDescription(request.description());
        badge.setRuleVersion(request.ruleVersion());
        badge.setRuleJson(json.valueToTree(request.rule()));
        badge.setStatus("active");
        repository.save(badge, actor.person().id());
        repository.flush();
        audit.record(actor.person().id(), "COMMUNITY_BADGE_CREATED", "community_badge", badge.getBadgeId().toString(),
            badge.getOwnerOrgId(), Map.of("code", badge.getCode(), "ruleVersion", badge.getRuleVersion()));
        return mapper.badge(badge);
    }

    @Transactional
    public BadgeView retire(Long badgeId, RetireBadge request) {
        writeLock.acquire();
        var actor = policy.actor();
        policy.requireModerator(actor);
        var badge = repository.require(CommunityBadge.class, badgeId);
        if (!Objects.equals(badge.getOwnerOrgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        policy.version(badge, request.expectedVersion());
        badge.setStatus("retired");
        repository.save(badge, actor.person().id());
        audit.record(actor.person().id(), "COMMUNITY_BADGE_RETIRED", "community_badge", badgeId.toString(),
            badge.getOwnerOrgId(), Map.of("reason", request.reason()));
        repository.flush();
        return mapper.badge(badge);
    }

    public PageResponse<BadgeAwardView> awards(String userId, int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        if (!Objects.equals(identity.activePerson(userId).orgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return PageResponse.from(badges.publicAwards(actor.person().orgId(), userId, page, size).map(this::award));
    }

    private List<BadgeAwardView> allAwards(String userId) {
        var actor = policy.actor();
        List<BadgeAwardView> result = new ArrayList<>();
        for (int page = 0; ; page++) {
            var values = badges.publicAwards(actor.person().orgId(), userId, page, 100);
            values.forEach(value -> result.add(award(value)));
            if (!values.hasNext()) {
                break;
            }
        }
        return result;
    }

    private BadgeAwardView award(BadgeAward value) {
        return new BadgeAwardView(value.getAwardId(), mapper.badge(repository.require(CommunityBadge.class,
            value.getBadgeId())), new PersonRef(value.getUserId(), identity.fullNames(List.of(value.getUserId()))
                .get(value.getUserId())), value.getStatus(), value.getAwardedAt(), value.getRevokedAt(),
                    value.getRevokeReason());
    }

    private ContributionView contribution(ContributionEvent value) {
        var content = policy.readable(value.getContentId(), policy.actor());
        var revision = repository.require(ContentRevision.class, content.getCurrentPublishedRevisionId());
        return new ContributionView(value.getContributionEventId(), value.getOrgId(), value.getUserId(),
            new ContentRef(content.getContentId(), content.getKind(), revision.getRevisionId(), revision.getTitle(),
                "/community/contents/" + content.getContentId()), value.getReasonCode(), value.getPointsDelta(),
            value.getPolicyVersion(), value.getReversesEventId(), value.getOccurredAt());
    }

    private long number(Object value) {
        return ((Number) value).longValue();
    }
}
