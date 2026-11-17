package vn.com.coreacademy.learning.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.learning.config.LearningMediaProperties;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.AuditAction;
import vn.com.coreacademy.learning.constants.LearningCodes.MediaKind;
import vn.com.coreacademy.learning.constants.LearningCodes.ProcessingError;
import vn.com.coreacademy.learning.constants.LearningCodes.UploadPartStatus;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.MediaRequests;
import vn.com.coreacademy.learning.dto.MediaViews;
import vn.com.coreacademy.learning.entity.AssetUploadPart;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.LearningAssetRepository;
import vn.com.coreacademy.learning.repository.LearningMediaRepository;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.learning.repository.custom.LearningAssetRepositoryCustom;
import vn.com.coreacademy.storage.config.S3Properties;
import vn.com.coreacademy.storage.service.MultipartObjectStorage;
import vn.com.coreacademy.storage.service.ObjectStorage;
import vn.com.coreacademy.storage.service.UploadStaging;

/** Điều phối manifest trong DB; mọi IO S3/staging nằm ngoài transaction và khóa dùng chung. */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningUploadService {
    private final LearningMediaProperties properties;
    private final IdentityAuditService audit;
    private final LearningRepository repository;
    private final LearningMediaRepository media;
    private final LearningAssetRepository assets;
    private final LearningAccessPolicy access;
    private final LearningWriteLock lock;
    private final MultipartObjectStorage multipart;
    private final ObjectStorage storage;
    private final UploadStaging staging;
    private final S3Properties s3;
    private final PlatformTransactionManager transactions;

    public MediaViews.Upload initiate(MediaRequests.Upload request) {
        LearningMediaProbe.validateName(request.originalName(), request.mimeType(), request.sizeBytes());
        UUID claim = UUID.randomUUID();
        LearningAsset snapshot = write(() -> {
            var actor = access.actor();
            access.requireManage(actor, request.ownerOrgId(), actor.person().teamId(), actor.person().id());
            LearningAsset asset = media.upload(request.requestId());
            if (asset == null) {
                asset = new LearningAsset();
                asset.setOwnerOrgId(request.ownerOrgId());
                asset.setUploadedBy(actor.person().id());
                asset.setUploadId(request.requestId());
                asset.setOriginalName(request.originalName());
                asset.setDisplayName(request.displayName() == null || request.displayName().isBlank()
                    ? request.originalName() : request.displayName().trim());
                asset.setDescription(request.description() == null || request.description().isBlank()
                    ? null : request.description().trim());
                asset.setMimeType(request.mimeType());
                asset.setMediaType(LearningMediaProbe.type(request.mimeType()));
                asset.setSizeBytes(request.sizeBytes());
                asset.setUploadTotalBytes(request.sizeBytes());
                asset.setUploadExpectedSha256(request.checksumSha256());
                asset.setStorageType(ObjectStorage.TYPE);
                asset.setStorageKey("learning/" + UUID.randomUUID());
                asset.setStatus(AssetStatus.UPLOADING);
                asset.setUploadExpiresAt(Instant.now().plus(properties.getUploadTtl()));
                repository.save(asset, actor.person().id());
                audit.record(actor.person().id(), AuditAction.UPLOAD_CREATED, "asset", asset.getId().toString(),
                    asset.getOwnerOrgId(), Map.of("sizeBytes", request.sizeBytes(), "mimeType", request.mimeType()));
            } else {
                authorize(asset);
                if (!Objects.equals(asset.getOwnerOrgId(), request.ownerOrgId())
                    || !Objects.equals(asset.getOriginalName(), request.originalName())
                    || !Objects.equals(asset.getMimeType(), request.mimeType())
                    || !Objects.equals(asset.getUploadTotalBytes(), request.sizeBytes())
                    || !Objects.equals(asset.getUploadExpectedSha256(), request.checksumSha256())) {
                    throw new BusinessException(LearningResponseCode.UPLOAD_FILE_MISMATCH);
                }
            }
            if (AssetStatus.UPLOADING.equals(asset.getStatus()) && asset.getS3UploadId() == null) {
                requireOpen(asset);
                if (asset.getProcessingLeaseExpiresAt() != null
                    && asset.getProcessingLeaseExpiresAt().isAfter(Instant.now())) {
                    throw new BusinessException(LearningResponseCode.ASSET_PROCESSING);
                }
                asset.setProcessingLeaseId(claim);
                asset.setProcessingLeaseExpiresAt(Instant.now()
                    .plusSeconds(s3.multipartCallTimeoutSeconds()).plus(properties.getReservationGrace()));
            }
            repository.flush();
            return asset;
        });
        if (claim.equals(snapshot.getProcessingLeaseId())) {
            String providerId = null;
            try {
                providerId = multipart.initiate(snapshot.getStorageKey());
                String issued = providerId;
                write(() -> {
                    LearningAsset current = own(request.requestId());
                    if (!claim.equals(current.getProcessingLeaseId()) ||
                        !AssetStatus.UPLOADING.equals(current.getStatus())) {
                        throw new BusinessException(LearningResponseCode.UPLOAD_EXPIRED);
                    }
                    current.setS3UploadId(issued);
                    current.setProcessingLeaseId(null);
                    current.setProcessingLeaseExpiresAt(null);
                    return null;
                });
            } catch (RuntimeException exception) {
                if (providerId != null) {
                    try {
                        multipart.abort(snapshot.getStorageKey(), providerId);
                    } catch (RuntimeException cleanup) {
                        exception.addSuppressed(cleanup);
                        log.error("Multipart init cleanup failed assetId={}", snapshot.getId());
                    }
                }
                recover(() -> write(() -> {
                    LearningAsset current = media.upload(request.requestId());
                    if (current != null && claim.equals(current.getProcessingLeaseId())) {
                        current.setProcessingLeaseId(null);
                        current.setProcessingLeaseExpiresAt(null);
                    }
                    return null;
                }), exception, "init");
                throw exception;
            }
        }
        return status(request.requestId());
    }

    @Transactional(readOnly = true)
    public MediaViews.Upload status(UUID id) {
        return view(own(id));
    }

    public MediaViews.Upload part(UUID id, int number, String checksum, InputStream input) throws IOException {
        if (number < 1 || checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw BusinessException.validation("part", "INVALID_FORMAT", "Số phần hoặc SHA-256 chưa hợp lệ.");
        }
        LearningAsset snapshot = new TransactionTemplate(transactions).execute(tx -> own(id));
        requireOpen(snapshot);
        long count = (snapshot.getUploadTotalBytes() + snapshot.getChunkSizeBytes() - 1)
            / snapshot.getChunkSizeBytes();
        if (number > count || count > LearningLimits.MAX_UPLOAD_PARTS || snapshot.getS3UploadId() == null) {
            throw BusinessException.validation("partNumber", "OUT_OF_RANGE", "Phần tải lên chưa hợp lệ.");
        }
        long expected = Math.min(snapshot.getChunkSizeBytes(), snapshot.getUploadTotalBytes()
            - (long) (number - 1) * snapshot.getChunkSizeBytes());
        try (var file = staging.stage(input, expected)) {
            if (file.size() != expected || !checksum.equals(file.checksum())) {
                throw new BusinessException(LearningResponseCode.UPLOAD_FILE_MISMATCH);
            }
            String reservation = UUID.randomUUID().toString();
            boolean send = write(() -> {
                LearningAsset asset = own(id);
                requireOpen(asset);
                AssetUploadPart part = media.parts(id).stream().filter(p -> p.getPartNumber() == number)
                    .findFirst().orElse(null);
                if (part != null) {
                    if (!checksum.equals(part.getChecksumSha256()) || part.getSizeBytes() != expected) {
                        throw new BusinessException(LearningResponseCode.UPLOAD_PART_CONFLICT);
                    }
                    if (UploadPartStatus.UPLOADED.equals(part.getPartStatus())) {
                        return false;
                    }
                    if (UploadPartStatus.UPLOADING.equals(part.getPartStatus()) && part.getUpdateTime() != null
                        && part.getUpdateTime().plusSeconds(s3.multipartCallTimeoutSeconds())
                            .plus(properties.getReservationGrace())
                            .isAfter(Instant.now())) {
                        throw new BusinessException(LearningResponseCode.ASSET_PROCESSING);
                    }
                } else {
                    part = new AssetUploadPart();
                    part.setUploadId(id);
                    part.setPartNumber(number);
                    part.setSizeBytes(expected);
                    part.setChecksumSha256(checksum);
                }
                part.setPartStatus(UploadPartStatus.UPLOADING);
                part.setEtag(reservation);
                repository.save(part, access.actor().person().id());
                repository.flush();
                return true;
            });
            if (!send) {
                return status(id);
            }
            try {
                String etag = multipart.put(snapshot.getStorageKey(), snapshot.getS3UploadId(), number,
                    file.path(), file.size(), file.checksum());
                write(() -> {
                    LearningAsset asset = own(id);
                    requireOpen(asset);
                    AssetUploadPart part = media.parts(id).stream().filter(p -> p.getPartNumber() == number)
                        .findFirst().orElseThrow();
                    if (!reservation.equals(part.getEtag())) {
                        throw new BusinessException(LearningResponseCode.UPLOAD_PART_CONFLICT);
                    }
                    part.setPartStatus(UploadPartStatus.UPLOADED);
                    part.setEtag(etag);
                    part.setCommittedAt(Instant.now());
                    repository.save(part, access.actor().person().id());
                    asset.setUploadedBytes(media.parts(id).stream().filter(p ->
                        UploadPartStatus.UPLOADED.equals(p.getPartStatus()))
                        .mapToLong(AssetUploadPart::getSizeBytes).sum());
                    repository.save(asset, access.actor().person().id());
                    return null;
                });
            } catch (IOException | RuntimeException exception) {
                recover(() -> write(() -> {
                    for (var part : media.parts(id)) {
                        if (part.getPartNumber() == number && reservation.equals(part.getEtag())) {
                            part.setPartStatus(UploadPartStatus.RETRY);
                            part.setEtag(null);
                        }
                    }
                    return null;
                }), exception, "part");
                throw exception;
            }
        } catch (UploadStaging.LimitExceededException exception) {
            throw BusinessException.validation("file", "OUT_OF_RANGE", "Phần tải lên vượt kích thước cho phép.");
        }
        return status(id);
    }

    public MediaViews.Upload complete(UUID id) {
        return write(() -> {
            LearningAsset asset = own(id);
            if (AssetStatus.FAILED.equals(asset.getStatus())
                && ProcessingError.UNAVAILABLE.equals(asset.getProcessingErrorCode())) {
                // Chỉ retry lỗi hạ tầng khi chủ phiên chủ động yêu cầu, không retry file sai/codec không hỗ trợ.
                audit.record(access.actor().person().id(), AuditAction.MEDIA_RETRY_REQUESTED, "asset",
                    asset.getId().toString(), asset.getOwnerOrgId(),
                    Map.of("previousAttempts", asset.getProcessingAttempts()));
                asset.setProcessingAttempts(0);
                asset.setProcessingLeaseId(null);
                asset.setProcessingLeaseExpiresAt(null);
                asset.setProcessingError(null);
                asset.setProcessingErrorCode(null);
                asset.setStatus(asset.getUploadCompletedAt() == null ? AssetStatus.COMPLETING : AssetStatus.PROCESSING);
            }
            if (Set.of(AssetStatus.COMPLETING, AssetStatus.PROCESSING, AssetStatus.READY,
                AssetStatus.FAILED).contains(asset.getStatus())) {
                return view(asset);
            }
            requireOpen(asset);
            List<AssetUploadPart> parts = media.parts(id);
            int count = (int) ((asset.getUploadTotalBytes() + asset.getChunkSizeBytes() - 1)
                / asset.getChunkSizeBytes());
            if (parts.size() != count || asset.getS3UploadId() == null) {
                throw new BusinessException(LearningResponseCode.UPLOAD_FILE_MISMATCH);
            }
            long total = 0;
            for (int index = 0; index < count; index++) {
                var part = parts.get(index);
                long expected = Math.min(asset.getChunkSizeBytes(), asset.getUploadTotalBytes() - total);
                if (part.getPartNumber() != index + 1 || !UploadPartStatus.UPLOADED.equals(part.getPartStatus())
                    || part.getSizeBytes() != expected || part.getEtag() == null || part.getCommittedAt() == null) {
                    throw new BusinessException(LearningResponseCode.UPLOAD_FILE_MISMATCH);
                }
                total += part.getSizeBytes();
            }
            asset.setUploadedBytes(total);
            asset.setStatus(AssetStatus.COMPLETING);
            repository.save(asset, access.actor().person().id());
            audit.record(access.actor().person().id(), AuditAction.UPLOAD_QUEUED, "asset", asset.getId().toString(),
                asset.getOwnerOrgId(), Map.of("partCount", count));
            return view(asset);
        });
    }

    /** Hủy chỉ khi caller yêu cầu; không có job tự động abort/xóa media. */
    public MediaViews.Upload cancel(UUID id) {
        LearningAsset snapshot = write(() -> {
            LearningAsset asset = own(id);
            if (AssetStatus.CANCELED.equals(asset.getStatus())) {
                return asset;
            }
            if (!Set.of(AssetStatus.UPLOADING, AssetStatus.CANCELING,
                AssetStatus.EXPIRED).contains(asset.getStatus())) {
                throw new BusinessException(LearningResponseCode.CONFLICT);
            }
            if (!AssetStatus.CANCELING.equals(asset.getStatus())) {
                audit.record(access.actor().person().id(), AuditAction.UPLOAD_CANCEL_REQUESTED, "asset",
                    asset.getId().toString(), asset.getOwnerOrgId(), Map.of());
            }
            asset.setStatus(AssetStatus.CANCELING);
            repository.save(asset, access.actor().person().id());
            return asset;
        });
        if (!AssetStatus.CANCELED.equals(snapshot.getStatus())) {
            multipart.abort(snapshot.getStorageKey(), snapshot.getS3UploadId());
            write(() -> {
                LearningAsset current = own(id);
                current.setStatus(AssetStatus.CANCELED);
                repository.save(current, access.actor().person().id());
                return null;
            });
        }
        return status(id);
    }

    public void updateMetadata(Long id, MediaRequests.AssetMetadata request) {
        write(() -> {
            LearningAsset asset = repository.require(LearningAsset.class, id);
            var actor = access.actor();
            access.requireManage(actor, asset.getOwnerOrgId(),
                actor.person().id().equals(asset.getUploadedBy()) ? actor.person().teamId() : null,
                asset.getUploadedBy());
            if (Boolean.TRUE.equals(asset.getIsDeleted())) {
                throw new BusinessException(LearningResponseCode.NOT_FOUND);
            }
            asset.setDisplayName(request.displayName().trim());
            asset.setDescription(request.description() == null || request.description().isBlank()
                ? null : request.description().trim());
            repository.save(asset, actor.person().id());
            return null;
        });
    }

    /** Khóa asset trước khi xóa S3; khi lỗi giữ trạng thái deleting để người dùng thử lại. */
    public void deletePermanently(Long id) {
        write(() -> {
            LearningAsset asset = repository.require(LearningAsset.class, id);
            var actor = access.actor();
            access.requireManage(actor, asset.getOwnerOrgId(),
                actor.person().id().equals(asset.getUploadedBy()) ? actor.person().teamId() : null,
                asset.getUploadedBy());
            return null;
        });
        storage.requireUnversionedBucket();
        LearningAsset snapshot = write(() -> {
            LearningAsset asset = repository.require(LearningAsset.class, id);
            var actor = access.actor();
            access.requireManage(actor, asset.getOwnerOrgId(),
                actor.person().id().equals(asset.getUploadedBy()) ? actor.person().teamId() : null,
                asset.getUploadedBy());
            if (Boolean.TRUE.equals(asset.getIsDeleted()) || media.referenced(id)) {
                throw new BusinessException(LearningResponseCode.ASSET_IN_USE);
            }
            if (!Set.of(AssetStatus.READY, AssetStatus.DELETING).contains(asset.getStatus())
                || !ObjectStorage.TYPE.equals(asset.getStorageType()) || asset.getStorageKey() == null) {
                throw new BusinessException(LearningResponseCode.ASSET_PROCESSING);
            }
            if ((asset.getStorageKey() != null && media.sharedObject(id, asset.getStorageKey()))
                || (asset.getPlaybackKey() != null && media.sharedObject(id, asset.getPlaybackKey()))) {
                throw new BusinessException(LearningResponseCode.ASSET_IN_USE);
            }
            asset.setStatus(AssetStatus.DELETING);
            repository.save(asset, actor.person().id());
            return asset;
        });
        if (snapshot.getPlaybackKey() != null) storage.delete(snapshot.getPlaybackKey());
        if (snapshot.getStorageKey() != null) storage.delete(snapshot.getStorageKey());
        write(() -> {
            LearningAsset asset = repository.require(LearningAsset.class, id);
            if (!Boolean.TRUE.equals(asset.getIsDeleted())) {
                asset.setIsDeleted(true);
                asset.setDeletedAt(Instant.now());
                asset.setStatus(AssetStatus.DELETED);
                repository.save(asset, access.actor().person().id());
                audit.record(access.actor().person().id(), AuditAction.ASSET_DELETED, "asset", id.toString(),
                    asset.getOwnerOrgId(), Map.of("permanent", true));
            }
            return null;
        });
    }

    public void delete(Long id) {
        write(() -> {
            LearningAsset asset = repository.require(LearningAsset.class, id);
            var actor = access.actor();
            access.requireManage(actor, asset.getOwnerOrgId(),
                actor.person().id().equals(asset.getUploadedBy()) ? actor.person().teamId() : null,
                asset.getUploadedBy());
            if (Boolean.TRUE.equals(asset.getIsDeleted())) {
                return null;
            }
            if (media.referenced(id)) {
                throw new BusinessException(LearningResponseCode.ASSET_IN_USE);
            }
            if (!Set.of(AssetStatus.READY, AssetStatus.FAILED, AssetStatus.CANCELED,
                AssetStatus.EXPIRED).contains(asset.getStatus())) {
                throw new BusinessException(LearningResponseCode.ASSET_PROCESSING);
            }
            asset.setIsDeleted(true);
            asset.setDeletedAt(Instant.now());
            asset.setStatus(AssetStatus.DELETED);
            repository.save(asset, access.actor().person().id());
            audit.record(actor.person().id(), AuditAction.ASSET_DELETED, "asset", id.toString(),
                asset.getOwnerOrgId(), Map.of());
            return null;
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<MediaViews.Asset> list(String q, String type, String status, int page, int size, String sort,
                                               boolean includeDeleting) {
        var actor = access.actor();
        if (type != null && !MediaKind.VALUES.contains(type)) {
            throw BusinessException.validation("mediaType", "INVALID_VALUE", "Loại tài nguyên chưa hợp lệ.");
        }
        if (status != null && !AssetStatus.SEARCHABLE_VALUES.contains(status)) {
            throw BusinessException.validation("status", "INVALID_VALUE", "Trạng thái chưa hợp lệ.");
        }
        return PageResponse.from(assets.search(new LearningAssetRepositoryCustom.Filter(actor.person().id(),
            actor.person().orgId(), actor.person().teamId(), access.searchScope(actor), q, type, status,
            includeDeleting, null, null, null),
            PageQuery.of(page, size, sort, Set.of("id", "name", "createTime"))));
    }

    /** Chỉ trả tài nguyên ready cùng loại, đơn vị khóa và scope của caller. */
    @Transactional(readOnly = true)
    public List<MediaViews.Asset> duplicates(MediaRequests.DuplicateCheck request) {
        var actor = access.actor();
        access.requireManage(actor, request.ownerOrgId(), actor.person().teamId(), actor.person().id());
        return assets.search(new LearningAssetRepositoryCustom.Filter(actor.person().id(), actor.person().orgId(),
            actor.person().teamId(), access.searchScope(actor), null,
            request.mediaType() == null ? MediaKind.VIDEO : request.mediaType(), AssetStatus.READY, false,
            request.ownerOrgId(), request.checksumSha256(), request.sizeBytes()),
            PageQuery.of(0, 10, "id,desc", Set.of("id", "name", "createTime"))).getContent();
    }

    private LearningAsset own(UUID id) {
        LearningAsset asset = media.upload(id);
        if (asset == null) {
            throw new BusinessException(LearningResponseCode.NOT_FOUND);
        }
        authorize(asset);
        return asset;
    }

    private void authorize(LearningAsset asset) {
        var actor = access.actor();
        if (!actor.person().id().equals(asset.getUploadedBy())) {
            throw new AccessDeniedException("Upload owner denied");
        }
        access.requireManage(actor, asset.getOwnerOrgId(), actor.person().teamId(), actor.person().id());
    }

    private void requireOpen(LearningAsset asset) {
        if (!AssetStatus.UPLOADING.equals(asset.getStatus()) || Boolean.TRUE.equals(asset.getIsDeleted())
            || asset.getUploadExpiresAt() == null || !asset.getUploadExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(LearningResponseCode.UPLOAD_EXPIRED);
        }
    }

    private MediaViews.Upload view(LearningAsset asset) {
        return new MediaViews.Upload(asset.getUploadId(), asset.getId(), asset.getStatus(),
            asset.getUploadTotalBytes(), asset.getUploadedBytes(), asset.getChunkSizeBytes(),
            asset.getUploadExpiresAt(), media.parts(asset.getUploadId()).stream()
                .filter(p -> UploadPartStatus.UPLOADED.equals(p.getPartStatus()))
                .map(p -> new MediaViews.Part(p.getPartNumber(), p.getSizeBytes(), p.getChecksumSha256())).toList(),
            asset.getProcessingErrorCode(), asset.getProcessingError(), asset.getMimeType(), asset.getDurationSeconds(),
            asset.getPageCount(), asset.getMediaCheckedAt(),
            AssetStatus.PROCESSING.equals(asset.getStatus()) ? asset.getProcessingProgress() : null);
    }

    private void recover(Runnable recovery, Throwable primary, String operation) {
        try {
            recovery.run();
        } catch (RuntimeException secondary) {
            primary.addSuppressed(secondary);
            log.error("Upload metadata recovery pending operation={} type={}", operation,
                secondary.getClass().getSimpleName());
        }
    }

    private <T> T write(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(tx -> {
            lock.acquire();
            return action.get();
        });
    }
}
