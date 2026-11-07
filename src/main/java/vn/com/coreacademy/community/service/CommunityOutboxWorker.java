package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.community.config.CommunityJobsProperties;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.NotificationDigestBatch;
import vn.com.coreacademy.community.repository.CommunityBadgeRepository;
import vn.com.coreacademy.community.repository.CommunityContributionRepository;
import vn.com.coreacademy.community.repository.CommunityFanoutRepository;
import vn.com.coreacademy.community.repository.CommunityIndexRepository;
import vn.com.coreacademy.community.repository.CommunityJobRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.learning.service.XpLedgerService;

/** Lease/dedup bền vững trong DB; retry reconcile trạng thái nguồn hiện tại, không cộng điểm mù theo thứ tự event. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommunityOutboxWorker {
    private final CommunityJobsProperties properties;
    private final CommunityRepository repository;
    private final CommunityJobRepository jobs;
    private final CommunityIndexRepository index;
    private final CommunityIndexService indexing;
    private final CommunityFanoutRepository fanout;
    private final CommunityFanoutService delivery;
    private final CommunityContributionRepository contributions;
    private final CommunityBadgeRepository badges;
    private final XpLedgerService xpLedger;
    private final IdentityAuditService audit;
    private final CommunityPolicy policy;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;
    private final PlatformTransactionManager transactions;
    private final ExecutorService communityJobsExecutor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Claim> currentClaim = new AtomicReference<>();
    private final AtomicLong heartbeatAfter = new AtomicLong();

    private record Claim(Long id, UUID token, boolean digest) {
    }

    @PostConstruct
    public void verifyActor() {
        if (properties.isEnabled()) {
            identity.activePerson(properties.getWorkerUserId());
        }
    }

    @Scheduled(fixedDelayString = "#{@communityJobsProperties.pollMs}",
        initialDelayString = "#{@communityJobsProperties.initialDelay.toMillis()}")
    public void poll() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            heartbeat();
            return;
        }
        communityJobsExecutor.execute(() -> {
            try {
                Claim claim = claim();
                if (claim != null) {
                    currentClaim.set(claim);
                    heartbeatAfter.set(System.currentTimeMillis() + properties.getHeartbeat().toMillis());
                    if (claim.digest()) {
                        digest(claim);
                    } else {
                        process(claim);
                    }
                }
            } catch (RuntimeException exception) {
                var claim = currentClaim.get();
                if (claim != null) {
                    fail(claim);
                }
                log.error("Community outbox cycle failed type={}", exception.getClass().getSimpleName());
            } finally {
                currentClaim.set(null);
                running.set(false);
            }
        });
    }

    private Claim claim() {
        return write(() -> {
            delivery.createDigestWindows(properties.getWorkerUserId());
            var batches = fanout.pendingBatches();
            if (!batches.isEmpty()) {
                var batch = batches.get(0);
                if (batch.getAttemptCount() >= 3) {
                    batch.setBatchStatus("failed");
                    repository.save(batch, properties.getWorkerUserId());
                    return null;
                }
                UUID token = UUID.randomUUID();
                batch.setLeaseToken(token);
                batch.setLeaseExpiresAt(Instant.now().plus(properties.getLease()));
                batch.setBatchStatus("processing");
                batch.setAttemptCount(batch.getAttemptCount() + 1);
                repository.save(batch, properties.getWorkerUserId());
                return new Claim(batch.getBatchId(), token, true);
            }
            var events = jobs.pending();
            if (events.isEmpty()) {
                return null;
            }
            var event = events.get(0);
            if (event.getAttemptCount() >= 3) {
                event.setEventStatus("failed");
                repository.save(event, properties.getWorkerUserId());
                return null;
            }
            UUID token = UUID.randomUUID();
            event.setLeaseToken(token);
            event.setLeaseExpiresAt(Instant.now().plus(properties.getLease()));
            event.setAttemptCount(event.getAttemptCount() + 1);
            event.setEventStatus("processing");
            repository.save(event, properties.getWorkerUserId());
            return new Claim(event.getEventId(), token, false);
        });
    }

    private void process(Claim claim) {
        var event = write(() -> current(claim));
        boolean reindex = "search_reindex".equals(event.getEventType());
        boolean refresh = "index_refresh".equals(event.getEventType());
        boolean reconcile = "contributions_reconcile".equals(event.getEventType());
        if (reindex || reconcile) {
            var creator = identity.actor(event.getActorUserId());
            if (!Objects.equals(creator.person().orgId(), event.getOwnerOrgId()) || !policy.isModerator(creator)) {
                throw new IllegalStateException("Job creator no longer has moderator scope");
            }
        }
        List<Long> explicit = new ArrayList<>();
        event.getPayloadJson().path("contentIds").forEach(value -> explicit.add(value.asLong()));
        long processed = 0;
        if (event.getContentId() != null) {
            for (Long contentId : write(() -> index.affected(event.getContentId()))) {
                processContent(claim, event, contentId, reindex || refresh);
                processed++;
            }
        } else if (reindex || reconcile) {
            if (!explicit.isEmpty()) {
                for (Long contentId : explicit) {
                    processContent(claim, event, contentId, reindex);
                    processed++;
                }
            } else {
                Long guild = event.getPayloadJson().hasNonNull("guildId") ? event.getPayloadJson().get("guildId")
                    .asLong() : null;
                long after = 0;
                while (true) {
                    long cursor = after;
                    var ids = write(() -> {
                        current(claim);
                        return index.contentIds(event.getOwnerOrgId(), cursor, guild);
                    });
                    if (ids.isEmpty()) {
                        break;
                    }
                    for (Long id : ids) {
                        processContent(claim, event, id, reindex);
                        processed++;
                        after = id;
                    }
                }
            }
        }
        if (!reindex && !reconcile && !refresh) {
            var audience = write(() -> delivery.audience(current(claim), properties.getWorkerUserId()));
            String after = "";
            while (true) {
                String cursor = after;
                var recipients = write(() -> delivery.deliver(current(claim), audience, cursor,
                    properties.getWorkerUserId()));
                if (recipients.isEmpty()) {
                    break;
                }
                after = recipients.get(recipients.size() - 1).userId();
            }
        }
        long total = processed;
        write(() -> {
            var current = current(claim);
            if (current.getContentId() != null && List.of("content_published", "transcript_published")
                .contains(current.getEventType())) {
                CommunityOutboxEvent next = new CommunityOutboxEvent();
                next.setOwnerOrgId(current.getOwnerOrgId());
                next.setContentId(current.getContentId());
                next.setActorUserId(properties.getWorkerUserId());
                next.setEventType("index_refresh");
                next.setPayloadJson(JsonNodeFactory.instance.objectNode());
                next.setDedupKey("index_refresh:" + current.getEventId());
                next.setEventStatus("pending");
                next.setAvailableAt(Instant.now());
                next.setAttemptCount(0);
                if (jobs.dedup(next.getDedupKey()).isEmpty()) {
                    repository.save(next, properties.getWorkerUserId());
                }
            }
            int changedAwards = badges.reconcileAwards(current.getOwnerOrgId(), current.getEventId(),
                properties.getWorkerUserId());
            if (changedAwards > 0) {
                audit.record(properties.getWorkerUserId(), "COMMUNITY_BADGE_AWARDS_RECONCILED", "community_event",
                    current.getEventId().toString(), current.getOwnerOrgId(),
                    Map.of("changedAwards", changedAwards));
            }
            current.setEventStatus("processed");
            current.setProcessedAt(Instant.now());
            current.setLeaseToken(null);
            current.setLeaseExpiresAt(null);
            ObjectNode data = (ObjectNode) current.getPayloadJson().deepCopy();
            data.put("processedCount", total);
            data.put("failedCount", 0);
            current.setPayloadJson(data);
            repository.save(current, properties.getWorkerUserId());
            return null;
        });
    }

    private void processContent(Claim claim, CommunityOutboxEvent event, Long id, boolean indexContent) {
        write(() -> {
            current(claim);
            if ("contributions_reconcile".equals(event.getEventType())) {
                List<String> users = new ArrayList<>();
                event.getPayloadJson().path("userIds").forEach(value -> users.add(value.asText()));
                contributions.reconcile(event.getOwnerOrgId(), id, event.getEventId(), properties.getWorkerUserId(),
                    users,
                    Instant.parse(event.getPayloadJson().path("from").asText()),
                    Instant.parse(event.getPayloadJson().path("to").asText()));
            } else {
                contributions.reconcile(event.getOwnerOrgId(), id, event.getEventId(), properties.getWorkerUserId());
            }
            // Cùng giao dịch với việc ghi điểm đóng góp: XP cộng đồng của nội dung này được ghi vào sổ XP.
            xpLedger.communityContributions(id);
            return null;
        });
        if (indexContent) {
            var plan = write(() -> {
                current(claim);
                return indexing.prepare(id, event.getOwnerOrgId());
            });
            var vectors = indexing.embed(plan);
            write(() -> {
                current(claim);
                indexing.publish(plan, vectors, properties.getWorkerUserId());
                return null;
            });
        }
    }

    private void digest(Claim claim) {
        while (true) {
            int count = write(() -> {
                var batch = currentBatch(claim);
                return delivery.claimDigestItems(batch, properties.getWorkerUserId()).size();
            });
            if (count == 0) {
                break;
            }
        }
        write(() -> {
            var batch = currentBatch(claim);
            batch.setBatchStatus("delivered");
            batch.setDeliveredAt(Instant.now());
            batch.setLeaseToken(null);
            batch.setLeaseExpiresAt(null);
            repository.save(batch, properties.getWorkerUserId());
            return null;
        });
    }

    private void heartbeat() {
        var claim = currentClaim.get();
        if (claim == null || System.currentTimeMillis() < heartbeatAfter.get()) {
            return;
        }
        write(() -> {
            if (claim.digest()) {
                var batch = currentBatch(claim);
                batch.setLeaseExpiresAt(Instant.now().plus(properties.getLease()));
                repository.save(batch, properties.getWorkerUserId());
            } else {
                var event = current(claim);
                event.setLeaseExpiresAt(Instant.now().plus(properties.getLease()));
                repository.save(event, properties.getWorkerUserId());
            }
            return null;
        });
        heartbeatAfter.set(System.currentTimeMillis() + properties.getHeartbeat().toMillis());
    }

    private CommunityOutboxEvent current(Claim claim) {
        var value = repository.require(CommunityOutboxEvent.class, claim.id());
        if (!claim.token().equals(value.getLeaseToken()) || value.getLeaseExpiresAt() == null
            || !value.getLeaseExpiresAt().isAfter(Instant.now()) || !"processing".equals(value.getEventStatus())) {
            throw new IllegalStateException("Outbox lease lost");
        }
        return value;
    }

    private NotificationDigestBatch currentBatch(Claim claim) {
        var value = repository.require(NotificationDigestBatch.class, claim.id());
        if (!claim.token().equals(value.getLeaseToken()) || value.getLeaseExpiresAt() == null
            || !value.getLeaseExpiresAt().isAfter(Instant.now())) {
            throw new IllegalStateException("Digest lease lost");
        }
        return value;
    }

    private void fail(Claim claim) {
        write(() -> {
            if (claim.digest()) {
                var batch = repository.require(NotificationDigestBatch.class, claim.id());
                if (claim.token().equals(batch.getLeaseToken())) {
                    batch.setBatchStatus(batch.getAttemptCount() >= 3 ? "failed" : "pending");
                    batch.setLastErrorCode("COMMUNITY-DIGEST_UNAVAILABLE");
                    batch.setLeaseToken(null);
                    batch.setLeaseExpiresAt(Instant.now().plus(batch.getAttemptCount() == 1
                        ? properties.getRetryDelay() : properties.getSecondRetryDelay()));
                    repository.save(batch, properties.getWorkerUserId());
                }
            } else {
                var event = repository.require(CommunityOutboxEvent.class, claim.id());
                if (claim.token().equals(event.getLeaseToken())) {
                    event.setEventStatus(event.getAttemptCount() >= 3 ? "failed" : "pending");
                    event.setLastErrorCode("COMMUNITY-JOB_UNAVAILABLE");
                    event.setAvailableAt(Instant.now().plus(event.getAttemptCount() == 1
                        ? properties.getRetryDelay() : properties.getSecondRetryDelay()));
                    event.setLeaseToken(null);
                    event.setLeaseExpiresAt(null);
                    repository.save(event, properties.getWorkerUserId());
                }
            }
            return null;
        });
    }

    private <T> T write(Supplier<T> operation) {
        return new TransactionTemplate(transactions).execute(status -> {
            writeLock.acquire();
            identity.activePerson(properties.getWorkerUserId());
            return operation.get();
        });
    }
}
