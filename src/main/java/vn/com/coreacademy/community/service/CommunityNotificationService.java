package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.NotificationDtos.DigestDetail;
import vn.com.coreacademy.community.dto.NotificationDtos.DigestView;
import vn.com.coreacademy.community.dto.NotificationDtos.NotificationView;
import vn.com.coreacademy.community.dto.NotificationDtos.Retry;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunityNotification;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.NotificationDigestBatch;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityNotificationRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityNotificationService {
    private final CommunityRepository repository;
    private final CommunityNotificationRepository notifications;
    private final CommunityPolicy policy;
    private final CommunityContentService contents;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    public PageResponse<NotificationView> list(boolean unread, int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        return PageResponse.from(notifications.visible(actor, unread, null, page, size).map(value -> view(value,
            actor)));
    }

    @Transactional
    public NotificationView read(Long notificationId) {
        writeLock.acquire();
        var actor = policy.actor();
        var value = repository.require(CommunityNotification.class, notificationId);
        var result = view(value, actor);
        if (value.getReadAt() == null) {
            value.setReadAt(Instant.now());
            repository.save(value, actor.person().id());
            result = view(value, actor);
        }
        return result;
    }

    public PageResponse<DigestView> batches(int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        return PageResponse.from(notifications.batches(actor, page, size).map(value -> digestView(value, actor)));
    }

    public DigestDetail detail(Long batchId, int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        var batch = ownBatch(batchId, actor);
        return new DigestDetail(digestView(batch, actor), PageResponse.from(notifications.visible(actor, false,
            batchId, page, size).map(value -> view(value, actor))));
    }

    /** Chỉ đánh dấu item còn ACL; không làm lộ item riêng tư cũ qua số lượng hoặc tiêu đề snapshot. */
    @Transactional
    public DigestView readBatch(Long batchId) {
        writeLock.acquire();
        var actor = policy.actor();
        var batch = ownBatch(batchId, actor);
        int page = 0;
        while (true) {
            var visible = notifications.visible(actor, true, batchId, 0, 100);
            if (visible.isEmpty()) {
                break;
            }
            for (var value : visible) {
                value.setReadAt(Instant.now());
                repository.save(value, actor.person().id());
            }
            repository.flush();
            if (++page > 10_000) {
                throw new BusinessException(CommunityResponseCode.INVALID_STATE);
            }
        }
        return digestView(batch, actor);
    }

    @Transactional
    public DigestView retry(Long batchId, Retry request) {
        writeLock.acquire();
        var actor = policy.actor();
        var batch = ownBatch(batchId, actor);
        if ("delivered".equals(batch.getBatchStatus())) {
            return digestView(batch, actor);
        }
        policy.version(batch, request.expectedVersion());
        if (!"failed".equals(batch.getBatchStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        batch.setBatchStatus("pending");
        batch.setAttemptCount(0);
        batch.setLastErrorCode(null);
        batch.setLeaseToken(null);
        batch.setLeaseExpiresAt(null);
        repository.save(batch, actor.person().id());
        audit.record(actor.person().id(), "COMMUNITY_DIGEST_RETRY", "community_digest", batchId.toString(),
            actor.person().orgId(), Map.of("status", "pending"));
        repository.flush();
        return digestView(batch, actor);
    }

    private NotificationDigestBatch ownBatch(Long id, Actor actor) {
        var batch = repository.require(NotificationDigestBatch.class, id);
        if (!Objects.equals(batch.getUserId(), actor.person().id())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return batch;
    }

    private DigestView digestView(NotificationDigestBatch batch, Actor actor) {
        return new DigestView(batch.getBatchId(), batch.getWindowStart(), batch.getWindowEnd(), batch.getBatchStatus(),
            batch.getDeliveredAt(), notifications.visibleCount(actor, batch.getBatchId(), false),
            notifications.visibleCount(actor, batch.getBatchId(), true), batch.getRowVersion(),
            batch.getCreateTime(), batch.getUpdateTime());
    }

    private NotificationView view(CommunityNotification value, Actor actor) {
        var event = repository.require(CommunityOutboxEvent.class, value.getEventId());
        if (!Objects.equals(value.getRecipientUserId(), actor.person().id())
            || !Objects.equals(event.getOwnerOrgId(), actor.person().orgId())
            || !"delivered".equals(value.getDeliveryStatus())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        ContentRef reference = null;
        boolean moderation = "content_moderated".equals(event.getEventType());
        if (moderation && value.getContentId() != null
            && !policy.canRead(repository.require(CommunityContent.class,
                value.getContentId()), actor)) {
            // Tác giả vẫn nhận kết quả điều phối, nhưng không nhận lại title/body/URL của nguồn đã bị ẩn.
            reference = null;
        } else if (value.getContentId() != null) {
            var content = policy.readable(value.getContentId(), actor);
            contents.requirePublished(content);
            reference = new ContentRef(content.getContentId(), content.getKind(),
                content.getCurrentPublishedRevisionId(),
                contents.detail(content, actor, false).publishedRevision().title(),
                "/community/contents/" + content.getContentId());
        } else if (event.getGuildId() != null) {
            // Cả GET trực tiếp và list phải kiểm lại cùng chính sách Guild ở repository.
            boolean visible = notifications.isVisible(value.getNotificationId(), actor);
            if (!visible) {
                throw new BusinessException(CommunityResponseCode.NOT_FOUND);
            }
        } else {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        String actorId = event.getActorUserId();
        PersonRef author = actorId == null ? null : new PersonRef(actorId, identity.fullNames(List.of(actorId))
            .get(actorId));
        return new NotificationView(value.getNotificationId(), event.getEventType(), reference, author,
            value.getReadAt(), value.getDeliveredAt(), value.getDeliveryMode(), moderation
                ? "Nội dung của bạn đã có quyết định điều phối." : "Có hoạt động mới trong cộng đồng.");
    }
}
