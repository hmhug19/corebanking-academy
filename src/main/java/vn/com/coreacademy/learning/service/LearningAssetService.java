package vn.com.coreacademy.learning.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.AuditAction;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.entity.LessonBlock;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LearningMapper;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.storage.dto.ObjectDownload;
import vn.com.coreacademy.storage.service.ObjectStorage;

@Service
@RequiredArgsConstructor
@Slf4j
public class LearningAssetService {
    private final LearningMediaTokenService mediaTokens;
    private final LearningAssetStore store;
    private final LearningRepository repository;
    private final LearningAccessPolicy access;
    private final LearningAssignmentService assignments;
    private final LearningSearchService search;
    private final LearningWriteLock lock;
    private final LearningMapper mapper;
    private final IdentityAuditService audit;
    private final PlatformTransactionManager transactionManager;

    public LearningViews.LearningAssetView upload(Long orgId, MultipartFile file, BigDecimal durationSeconds,
                                                  Integer pageCount) throws IOException {
        // Bước 1: xác thực scope trước khi ghi S3; upload ngoài transaction để không giữ DB lock khi truyền file.
        Actor actor = access.actor();
        access.requireManage(actor, orgId, actor.person().teamId(), actor.person().id());
        LearningAssetStore.Stored stored = store.store(file, durationSeconds, pageCount);
        try {
            var result = new TransactionTemplate(transactionManager).execute(status -> {
                // Bước 2: kiểm tra lại quyền và ghi metadata/audit nguyên tử sau khi file đã sẵn sàng.
                var current = access.actor();
                access.requireManage(current, orgId, current.person().teamId(), current.person().id());
                lock.acquire();
                LearningAsset asset = new LearningAsset();
                asset.setOwnerOrgId(orgId);
                asset.setUploadedBy(current.person().id());
                asset.setStorageType(ObjectStorage.TYPE);
                asset.setStorageKey(stored.key());
                asset.setOriginalName(stored.name());
                asset.setDisplayName(stored.name());
                asset.setMimeType(stored.mime());
                asset.setSizeBytes(stored.size());
                asset.setChecksumSha256(stored.checksum());
                asset.setDurationSeconds(stored.duration());
                asset.setPageCount(stored.pages());
                asset.setMediaType(LearningMediaProbe.type(stored.mime()));
                asset.setMediaCheckedAt(Instant.now());
                asset.setPageCountCheckedAt(stored.pages() == null ? null : Instant.now());
                asset.setPlaybackKey(stored.playbackKey());
                asset.setPlaybackSizeBytes(stored.playbackSize());
                asset.setPlaybackChecksumSha256(stored.playbackChecksum());
                asset.setPlaybackMimeType(stored.playbackKey() == null ? null : stored.mime());
                asset.setStatus(AssetStatus.READY);
                repository.save(asset, current.person().id());
                audit.record(current.person().id(), AuditAction.ASSET_UPLOADED, "asset", asset.getId().toString(),
                    orgId, Map.of("mimeType", asset.getMimeType(), "sizeBytes", asset.getSizeBytes()));
                repository.flush();
                return mapper.view(asset);
            });
            return result;
        } catch (RuntimeException | Error exception) {
            // Giữ lỗi gốc nếu dọn S3 thất bại; DB và object store không có transaction chung.
            try {
                if (stored.playbackKey() != null) {
                    store.discard(stored.playbackKey());
                }
                store.discard(stored.key());
            } catch (RuntimeException cleanup) {
                log.error("S3 metadata rollback cleanup failed; reconcile objectKey={}", stored.key());
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public ObjectDownload download(Long assetId, Long blockId, Long enrollmentId) {
        var actor = access.actor();
        LearningAsset asset = repository.require(LearningAsset.class, assetId);
        boolean allowed = access.canManage(actor, asset.getOwnerOrgId(),
            actor.person().id().equals(asset.getUploadedBy()) ? actor.person().teamId() : null,
            asset.getUploadedBy());
        // Người học phải chứng minh asset được dùng trong đúng block/revision của enrollment của chính mình.
        if (blockId != null && enrollmentId != null) {
            mediaTokens.require(enrollmentId, blockId);
            var enrollment = assignments.requireOwnEnrollment(actor, enrollmentId, true);
            var revision = revisionOf(blockId);
            allowed = enrollment.getRevisionId().equals(revision.getId())
                && (assetId.equals(repository.require(LessonBlock.class, blockId).getMediaAssetId())
                    || repository.assetUses(assetId).stream().anyMatch(use -> use.getBlockId().equals(blockId)));
        } else {
            // Giảng viên chỉ tải khi có quyền sửa một khóa thực sự đang dùng file, hoặc chính người upload.
            if (asset.getUploadedBy().equals(actor.person().id())) {
                allowed = access.canManage(actor, asset.getOwnerOrgId(), actor.person().teamId(), actor.person().id());
            }
            for (var use : repository.assetUses(assetId)) {
                CourseRevision revision = revisionOf(use.getBlockId());
                Course course = repository.require(Course.class, revision.getCourseId());
                allowed |= access.canManage(actor, course.getOwnerOrgId(), course.getOwnerTeamId(),
                    course.getOwnerUserId());
            }
            boolean learner = access.isLearner(actor);
            for (var revision : repository.assetCovers(assetId)) {
                Course course = repository.require(Course.class, revision.getCourseId());
                allowed |= access.canManage(actor, course.getOwnerOrgId(), course.getOwnerTeamId(),
                    course.getOwnerUserId());
                // Ảnh bìa là một phần của thẻ khóa: học viên thấy revision này trong danh mục thì được xem.
                allowed = allowed || learner && search.seesRevision(actor, course.getId(), revision.getId());
            }
        }
        if (!allowed || !AssetStatus.READY.equals(asset.getStatus()) || Boolean.TRUE.equals(asset.getIsDeleted())) {
            throw new AccessDeniedException("Asset access denied");
        }
        if (!ObjectStorage.TYPE.equals(asset.getStorageType())) {
            throw new BusinessException(LearningResponseCode.ASSET_STORAGE_UNSUPPORTED);
        }
        if (asset.getStorageKey() == null || asset.getSizeBytes() == null || asset.getSizeBytes() <= 0
            || (asset.getPlaybackKey() != null && (asset.getPlaybackSizeBytes() == null
                || asset.getPlaybackSizeBytes() <= 0 || asset.getPlaybackMimeType() == null))) {
            throw new IllegalStateException("Stored media metadata incomplete; review legacy asset");
        }
        return new ObjectDownload(asset.getPlaybackKey() == null ? asset.getStorageKey() : asset.getPlaybackKey(),
            asset.getPlaybackKey() == null ? asset.getMimeType() : asset.getPlaybackMimeType(), asset.getOriginalName(),
            asset.getPlaybackKey() == null ? asset.getSizeBytes() : asset.getPlaybackSizeBytes());
    }

    private CourseRevision revisionOf(Long blockId) {
        LessonBlock block = repository.require(LessonBlock.class, blockId);
        CourseLesson lesson = repository.require(CourseLesson.class, block.getLessonId());
        CourseChapter chapter = repository.require(CourseChapter.class, lesson.getChapterId());
        return repository.require(CourseRevision.class, chapter.getRevisionId());
    }
}
