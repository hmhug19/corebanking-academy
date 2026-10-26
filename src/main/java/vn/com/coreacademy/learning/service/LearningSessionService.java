package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.config.LearningMediaProperties;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetUsage;
import vn.com.coreacademy.learning.constants.LearningCodes.AuditAction;
import vn.com.coreacademy.learning.constants.LearningCodes.BlockType;
import vn.com.coreacademy.learning.constants.LearningCodes.LeaseState;
import vn.com.coreacademy.learning.dto.MediaRequests;
import vn.com.coreacademy.learning.dto.MediaViews;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.entity.LearningUserLease;
import vn.com.coreacademy.learning.entity.LessonBlock;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LearningMapper;
import vn.com.coreacademy.learning.repository.LearningMediaRepository;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

/** Một lease trên tài khoản; serialize bằng khóa hiện có, không giữ khóa trong IO media. */
@Service
@RequiredArgsConstructor
public class LearningSessionService {
    private final LearningMediaProperties properties;
    private final IdentityAuditService audit;
    private final LearningRepository repository;
    private final LearningMediaRepository media;
    private final LearningAccessPolicy access;
    private final LearningAssignmentService assignments;
    private final LearningWriteLock lock;
    private final LearningMapper mapper;

    @Transactional
    public MediaViews.Session claim(MediaRequests.Session request) {
        lock.acquire();
        var actor = access.actor();
        var enrollment = assignments.requireOwnEnrollment(actor, request.enrollmentId(), true);
        LessonBlock block = repository.require(LessonBlock.class, request.blockId());
        CourseLesson lesson = repository.require(CourseLesson.class, block.getLessonId());
        CourseChapter chapter = repository.require(CourseChapter.class, lesson.getChapterId());
        if (!enrollment.getRevisionId().equals(chapter.getRevisionId())) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        Long assetId = primary(block, enrollment.getRevisionId());
        Instant now = Instant.now();
        LearningUserLease lease = media.lease(actor.person().id());
        boolean same = lease != null && lease.getSessionId() != null
            && lease.getSessionId().equals(request.previousSessionId())
            && Objects.equals(lease.getSessionFence(), request.previousFence());
        boolean transferred = active(lease, now) && !same;
        if (transferred && !request.takeover()) {
            throw new BusinessException(LearningResponseCode.SESSION_IN_USE);
        }
        if (!(active(lease, now) && same && Objects.equals(lease.getEnrollmentId(), request.enrollmentId())
            && Objects.equals(lease.getBlockId(), request.blockId()))) {
            if (lease == null) {
                lease = new LearningUserLease();
                lease.setUserId(actor.person().id());
                lease.setSessionFence(0L);
            }
            lease.setSessionId(UUID.randomUUID());
            lease.setSessionFence(Math.addExact(lease.getSessionFence(), 1L));
            lease.setLeaseState(LeaseState.ACTIVE);
            lease.setEnrollmentId(request.enrollmentId());
            lease.setBlockId(request.blockId());
            lease.setMediaAssetId(assetId);
            lease.setExpiresAt(now.plus(properties.getSessionLease()));
            lease.setLastHeartbeatAt(now);
            lease.setLastEventSequence(0L);
            lease.setLastEventHash(null);
            lease.setLastEventKind(null);
            lease.setLastEventAcceptedAt(null);
            repository.save(lease, actor.person().id());
            if (transferred) {
                audit.record(actor.person().id(), AuditAction.SESSION_TRANSFERRED, "learning_user",
                    actor.person().id(), actor.person().orgId(), Map.of("enrollmentId", request.enrollmentId(),
                        "blockId", request.blockId(), "fence", lease.getSessionFence()));
            }
            repository.flush();
        }
        var progress = repository.blockProgress(request.enrollmentId()).stream()
            .filter(p -> p.getBlockId().equals(request.blockId())).findFirst().orElse(null);
        return new MediaViews.Session(lease.getSessionId(), lease.getSessionFence(), lease.getExpiresAt(),
            lease.getLastEventSequence(), lease.getEnrollmentId(), lease.getBlockId(), assetId,
            progress == null ? 0 : progress.getRowVersion(), progress == null ? null : mapper.view(progress));
    }

    public LearningUserLease require(UUID sessionId, Long fence, Long enrollmentId, Long blockId) {
        LearningUserLease lease = media.lease(SecurityContextUtils.getUserId());
        if (lease == null || !Objects.equals(lease.getSessionId(), sessionId)
            || !Objects.equals(lease.getSessionFence(), fence)
            || !Objects.equals(lease.getEnrollmentId(), enrollmentId) || !Objects.equals(lease.getBlockId(), blockId)) {
            throw new BusinessException(LearningResponseCode.SESSION_EXPIRED);
        }
        return lease;
    }

    public void requireActive(LearningUserLease lease) {
        if (!active(lease, Instant.now())) {
            throw new BusinessException(LearningResponseCode.SESSION_EXPIRED);
        }
    }

    public Long primary(LessonBlock block, Long revisionId) {
        var links = repository.assetLinks(revisionId).stream()
            .filter(a -> a.getBlockId().equals(block.getId()) && AssetUsage.PRIMARY.equals(a.getUsage())).toList();
        Long id = block.getMediaAssetId();
        if (id == null && links.size() == 1) {
            id = links.get(0).getAssetId();
        }
        if (links.size() > 1 || (id != null && !links.isEmpty() && !id.equals(links.get(0).getAssetId()))) {
            throw new BusinessException(LearningResponseCode.MEDIA_CHANGED);
        }
        if (id == null && (BlockType.VIDEO.equals(block.getBlockType()) ||
            BlockType.PDF.equals(block.getBlockType()))) {
            throw new BusinessException(LearningResponseCode.ASSET_INVALID);
        }
        if (id != null) {
            LearningAsset asset = repository.require(LearningAsset.class, id);
            if (!AssetStatus.READY.equals(asset.getStatus()) || Boolean.TRUE.equals(asset.getIsDeleted())) {
                throw new BusinessException(LearningResponseCode.ASSET_PROCESSING);
            }
        }
        return id;
    }

    public boolean active(LearningUserLease lease, Instant now) {
        return lease != null && LeaseState.ACTIVE.equals(lease.getLeaseState()) && lease.getExpiresAt() != null
            && lease.getExpiresAt().isAfter(now);
    }
}
