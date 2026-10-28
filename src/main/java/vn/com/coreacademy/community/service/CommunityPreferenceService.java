package vn.com.coreacademy.community.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.PreferenceDtos.ExpertView;
import vn.com.coreacademy.community.dto.PreferenceDtos.ExpertiseResult;
import vn.com.coreacademy.community.dto.PreferenceDtos.ExpertiseView;
import vn.com.coreacademy.community.dto.PreferenceDtos.GuildRef;
import vn.com.coreacademy.community.dto.PreferenceDtos.Onboarding;
import vn.com.coreacademy.community.dto.PreferenceDtos.PreferencesView;
import vn.com.coreacademy.community.dto.PreferenceDtos.SaveExpertise;
import vn.com.coreacademy.community.dto.PreferenceDtos.SavePreferences;
import vn.com.coreacademy.community.dto.PreferenceDtos.SubscriptionRequest;
import vn.com.coreacademy.community.dto.PreferenceDtos.SubscriptionView;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunitySubscription;
import vn.com.coreacademy.community.entity.CommunityTag;
import vn.com.coreacademy.community.entity.CommunityUserPreferences;
import vn.com.coreacademy.community.entity.UserExpertise;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityPreferenceRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.GuildRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@RequiredArgsConstructor
public class CommunityPreferenceService {
    private static final Set<String> OPTIONAL_EVENTS = Set.of("reply", "mention", "invitation", "subscription");
    private final CommunityRepository repository;
    private final CommunityPreferenceRepository preferences;
    private final CommunityPolicy policy;
    private final CommunityGuildService guilds;
    private final CommunityContentService contents;
    private final CommunityTagService tags;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;
    private final GuildRepository guildRepository;

    @Transactional
    public PreferencesView get() {
        writeLock.acquire();
        var actor = policy.actor();
        return view(require(actor));
    }

    @Transactional
    public PreferencesView save(SavePreferences request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityUserPreferences value = require(actor);
        policy.version(value, request.expectedVersion());
        if (!ZoneId.getAvailableZoneIds().contains(request.timeZone())
            || "weekly".equals(request.digestFrequency()) != (request.digestWeekday() != null)
            || !request.notificationPreferences().isObject()) {
            throw BusinessException.validation("timeZone", "INVALID_VALUE", "Múi giờ hoặc lịch digest chưa hợp lệ.");
        }
        request.notificationPreferences().fields().forEachRemaining(field -> {
            if (!OPTIONAL_EVENTS.contains(field.getKey()) || !field.getValue().isBoolean()) {
                throw BusinessException.validation("notificationPreferences", "INVALID_VALUE",
                    "Chỉ nhận tùy chọn boolean cho loại thông báo được hỗ trợ.");
            }
        });
        value.setTimeZone(request.timeZone());
        value.setDigestFrequency(request.digestFrequency());
        value.setDigestLocalTime(request.digestLocalTime());
        value.setDigestWeekday(request.digestWeekday());
        value.setNotificationPreferencesJson(request.notificationPreferences().deepCopy());
        value.setNextDigestAt(nextDigest(value, Instant.now()));
        repository.incrementVersion(value, actor.person().id());
        repository.flush();
        return view(value);
    }

    @Transactional
    public PreferencesView onboarding(Onboarding request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityUserPreferences value = require(actor);
        policy.version(value, request.expectedVersion());
        if (new HashSet<>(request.tagIds()).size() != request.tagIds().size()
            || new HashSet<>(request.guildIdsToFollow()).size() != request.guildIdsToFollow().size()) {
            throw new BusinessException(CommunityResponseCode.DUPLICATE);
        }
        for (Long tag : request.tagIds()) {
            setSubscription(actor, new SubscriptionRequest(null, tag, null, null, "instant"));
        }
        for (Long guild : request.guildIdsToFollow()) {
            setSubscription(actor, new SubscriptionRequest(null, null, guild, null, "instant"));
        }
        if (request.complete() && value.getOnboardingCompletedAt() == null) {
            value.setOnboardingCompletedAt(Instant.now());
        }
        repository.incrementVersion(value, actor.person().id());
        repository.flush();
        return view(value);
    }

    @Transactional
    public SubscriptionView subscribe(SubscriptionRequest request) {
        writeLock.acquire();
        var actor = policy.actor();
        return subscription(setSubscription(actor, request), actor);
    }

    CommunitySubscription setSubscription(Actor actor, SubscriptionRequest request) {
        int targets = (request.contentId() == null ? 0 : 1) + (request.tagId() == null ? 0 : 1)
            + (request.guildId() == null ? 0 : 1) + (request.authorUserId() == null ? 0 : 1);
        if (targets != 1) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        CommunitySubscription candidate = new CommunitySubscription();
        candidate.setUserId(actor.person().id());
        candidate.setContentId(request.contentId());
        candidate.setTagId(request.tagId());
        candidate.setGuildId(request.guildId());
        candidate.setAuthorUserId(request.authorUserId());
        candidate.setDeliveryMode(request.mode());
        subscription(candidate, actor);
        if ("digest".equals(request.mode()) && "off".equals(require(actor).getDigestFrequency())) {
            throw BusinessException.validation("mode", "DIGEST_DISABLED", "Hãy cấu hình lịch digest trước.");
        }
        CommunitySubscription existing = preferences.subscriptions(actor.person().id()).stream()
            .filter(value -> Objects.equals(value.getContentId(), request.contentId())
                && Objects.equals(value.getTagId(), request.tagId())
                && Objects.equals(value.getGuildId(), request.guildId())
                && Objects.equals(value.getAuthorUserId(), request.authorUserId())).findFirst().orElse(null);
        if ("off".equals(request.mode())) {
            if (existing != null) {
                repository.remove(existing);
            }
            return null;
        }
        if (existing == null) {
            existing = candidate;
        }
        existing.setDeliveryMode(request.mode());
        repository.save(existing, actor.person().id());
        repository.flush();
        return existing;
    }

    public SubscriptionView subscription(CommunitySubscription value, Actor actor) {
        if (value == null) {
            return null;
        }
        String type;
        String id;
        String label;
        if (value.getContentId() != null) {
            CommunityContent content = policy.readable(value.getContentId(), actor);
            contents.requirePublished(content);
            type = "content";
            id = content.getContentId().toString();
            label = contents.detail(content, actor, false).publishedRevision().title();
        } else if (value.getTagId() != null) {
            CommunityTag tag = activeTag(value.getTagId(), actor);
            type = "tag";
            id = tag.getTagId().toString();
            label = tag.getName();
        } else if (value.getGuildId() != null) {
            var guild = guilds.readable(value.getGuildId(), actor);
            type = "guild";
            id = guild.getGuildId().toString();
            label = guild.getName();
        } else {
            var person = identity.activePerson(value.getAuthorUserId());
            if (!Objects.equals(person.orgId(), actor.person().orgId())) {
                throw new BusinessException(CommunityResponseCode.NOT_FOUND);
            }
            type = "author";
            id = person.id();
            label = person.name();
        }
        return new SubscriptionView(value.getSubscriptionId(), value.getUserId(), type, id, label,
            value.getDeliveryMode(), value.getRowVersion(), value.getCreateTime(), value.getUpdateTime());
    }

    @Transactional(readOnly = true)
    public PageResponse<SubscriptionView> subscriptions(int page, int size) {
        var actor = policy.actor();
        CommunityPages.check(page, size);
        return PageResponse.from(preferences.visibleSubscriptions(actor, page, size)
            .map(value -> subscription(value, actor)));
    }

    @Transactional(readOnly = true)
    public PageResponse<ExpertView> experts(String q, List<Long> tagIds, Long guildId, Boolean available,
                                           int page, int size) {
        var actor = policy.actor();
        CommunityPages.check(page, size);
        if (q != null && q.length() > 300 || tagIds != null && (tagIds.size() > 10
            || tagIds.stream().anyMatch(id -> id == null || id < 1))) {
            throw BusinessException.validation("q", "INVALID_VALUE", "Bộ lọc danh bạ chưa hợp lệ.");
        }
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        return PageResponse.from(preferences.experts(actor, q, tagIds, guildId, available, page, size).map(userId -> {
            var person = identity.activePerson(userId);
            var skills = expertiseViews(userId);
            var visibleGuilds = guildRepository.visibleForExpert(userId, actor.person().id(), actor.person().orgId())
                .stream().map(guild -> new GuildRef(guild.getGuildId(), guild.getName())).toList();
            return new ExpertView(new PersonRef(person.id(), person.name()), skills, visibleGuilds,
                skills.stream().anyMatch(ExpertiseView::availableForHelp));
        }));
    }

    @Transactional(readOnly = true)
    public List<ExpertiseView> expertise(String userId) {
        var actor = policy.actor();
        var person = identity.activePerson(userId);
        if (!Objects.equals(person.orgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return expertiseViews(userId);
    }

    @Transactional
    public ExpertiseResult expertise(SaveExpertise request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityUserPreferences value = require(actor);
        policy.version(value, request.expectedPreferenceVersion());
        Set<Long> ids = new HashSet<>();
        for (var input : request.items()) {
            activeTag(input.tagId(), actor);
            if (!ids.add(input.tagId())) {
                throw new BusinessException(CommunityResponseCode.DUPLICATE);
            }
        }
        preferences.expertise(actor.person().id()).forEach(repository::remove);
        for (var input : request.items()) {
            UserExpertise skill = new UserExpertise();
            skill.setUserId(actor.person().id());
            skill.setTagId(input.tagId());
            skill.setDescription(input.description());
            skill.setAvailableForHelp(input.availableForHelp());
            repository.save(skill, actor.person().id());
        }
        repository.incrementVersion(value, actor.person().id());
        repository.flush();
        return new ExpertiseResult(expertiseViews(actor.person().id()), value.getRowVersion());
    }

    private List<ExpertiseView> expertiseViews(String userId) {
        var person = identity.activePerson(userId);
        List<ExpertiseView> views = new ArrayList<>();
        for (UserExpertise skill : preferences.expertise(userId)) {
            CommunityTag tag = repository.require(CommunityTag.class, skill.getTagId());
            views.add(new ExpertiseView(skill.getExpertiseId(),
                new PersonRef(person.id(), person.name()),
                tags.view(tag), skill.getDescription(), skill.getAvailableForHelp()));
        }
        return List.copyOf(views);
    }

    public CommunityUserPreferences require(Actor actor) {
        CommunityUserPreferences value = repository.find(CommunityUserPreferences.class, actor.person().id());
        if (value == null) {
            value = new CommunityUserPreferences();
            value.setUserId(actor.person().id());
            value.setTimeZone("Asia/Ho_Chi_Minh");
            value.setDigestFrequency("off");
            value.setDigestLocalTime(LocalTime.of(8, 0));
            value.setNotificationPreferencesJson(JsonNodeFactory.instance.objectNode());
            repository.save(value, actor.person().id());
            repository.flush();
        }
        return value;
    }

    public Instant nextDigest(CommunityUserPreferences value, Instant after) {
        if ("off".equals(value.getDigestFrequency())) {
            return null;
        }
        ZoneId zone = ZoneId.of(value.getTimeZone());
        ZonedDateTime local = after.atZone(zone);
        var date = local.toLocalDate();
        if ("weekly".equals(value.getDigestFrequency())) {
            date = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(value.getDigestWeekday())));
        }
        ZonedDateTime candidate = date.atTime(value.getDigestLocalTime()).atZone(zone);
        if (!candidate.toInstant().isAfter(after)) {
            candidate = ("weekly".equals(value.getDigestFrequency()) ? date.plusWeeks(1) : date.plusDays(1))
                .atTime(value.getDigestLocalTime()).atZone(zone);
        }
        return candidate.toInstant();
    }

    private CommunityTag activeTag(Long id, Actor actor) {
        CommunityTag tag = repository.require(CommunityTag.class, id);
        if (!Objects.equals(tag.getOwnerOrgId(), actor.person().orgId())
            || !CommunityCodes.ACTIVE.equals(tag.getStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        return tag;
    }

    private PreferencesView view(CommunityUserPreferences value) {
        return new PreferencesView(value.getUserId(), value.getOnboardingCompletedAt(), value.getTimeZone(),
            value.getDigestFrequency(), value.getDigestLocalTime(), value.getDigestWeekday(), value.getNextDigestAt(),
            value.getNotificationPreferencesJson(), value.getRowVersion(), value.getCreateTime(),
                value.getUpdateTime());
    }
}
