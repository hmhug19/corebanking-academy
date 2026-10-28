package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.JobDtos.JobView;
import vn.com.coreacademy.community.dto.JobDtos.Reconcile;
import vn.com.coreacademy.community.dto.JobDtos.Reindex;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.Guild;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityJobRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityJobService {
    private final CommunityRepository repository;
    private final CommunityJobRepository jobs;
    private final CommunityPolicy policy;
    private final CommunityPayload payload;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;

    @Transactional
    public JobView reindex(Reindex request) {
        writeLock.acquire();
        var actor = policy.actor();
        policy.requireModerator(actor);
        var ids = request.contentIds() == null ? List.<Long>of() : request.contentIds();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(CommunityResponseCode.DUPLICATE);
        }
        for (Long id : ids) {
            var content = repository.require(CommunityContent.class, id);
            sameOrg(content.getOwnerOrgId(), actor);
        }
        if (request.guildId() != null) {
            sameOrg(repository.require(Guild.class, request.guildId()).getOwnerOrgId(), actor);
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        var array = data.putArray("contentIds");
        ids.forEach(array::add);
        if (request.guildId() != null) {
            data.put("guildId", request.guildId());
        }
        return enqueue("search_reindex", request.requestId(), payload.hash("search_reindex", request), data, actor);
    }

    @Transactional
    public JobView reconcile(Reconcile request) {
        writeLock.acquire();
        var actor = policy.actor();
        policy.requireModerator(actor);
        var ids = request.userIds() == null ? List.<String>of() : request.userIds();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(CommunityResponseCode.DUPLICATE);
        }
        ids.forEach(id -> sameOrg(identity.activePerson(id).orgId(), actor));
        var period = CommunityAnalyticsService.period(request.from(), request.to());
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        var array = data.putArray("userIds");
        ids.forEach(array::add);
        data.put("from", period.from().toString());
        data.put("to", period.to().toString());
        return enqueue("contributions_reconcile", request.requestId(), payload.hash("contributions_reconcile", request),
            data, actor);
    }

    public JobView get(Long eventId) {
        var actor = policy.actor();
        var event = repository.require(CommunityOutboxEvent.class, eventId);
        sameOrg(event.getOwnerOrgId(), actor);
        if (!List.of("search_reindex", "contributions_reconcile").contains(event.getEventType())
            || !Objects.equals(event.getActorUserId(), actor.person().id()) && !policy.isModerator(actor)) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return view(event);
    }

    private JobView enqueue(String type, UUID requestId, String hash, ObjectNode data, Actor actor) {
        String key = type + ":" + actor.person().id() + ":" + requestId;
        var previous = jobs.dedup(key);
        if (!previous.isEmpty()) {
            var event = previous.get(0);
            sameOrg(event.getOwnerOrgId(), actor);
            if (!hash.equals(event.getPayloadJson().path("requestHash").asText())) {
                throw new BusinessException(CommunityResponseCode.IDEMPOTENCY_CONFLICT);
            }
            return view(event);
        }
        data.put("requestHash", hash);
        data.put("ownerOrgId", actor.person().orgId());
        data.put("processedCount", 0);
        data.put("failedCount", 0);
        CommunityOutboxEvent event = new CommunityOutboxEvent();
        event.setOwnerOrgId(actor.person().orgId());
        event.setEventType(type);
        event.setActorUserId(actor.person().id());
        event.setDedupKey(key);
        event.setPayloadJson(data);
        event.setEventStatus("pending");
        event.setAvailableAt(Instant.now());
        event.setAttemptCount(0);
        repository.save(event, actor.person().id());
        repository.flush();
        return view(event);
    }

    private void sameOrg(Long orgId, Actor actor) {
        if (!Objects.equals(orgId, actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
    }

    private JobView view(CommunityOutboxEvent event) {
        return new JobView(event.getEventId(), event.getEventStatus(), event.getPayloadJson().path("processedCount")
            .asLong(),
            event.getPayloadJson().path("failedCount").asLong(), event.getLastErrorCode());
    }
}
