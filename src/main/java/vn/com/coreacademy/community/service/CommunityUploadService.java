package vn.com.coreacademy.community.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.config.CommunityProperties;
import vn.com.coreacademy.community.dto.MediaDtos.PartAck;
import vn.com.coreacademy.community.dto.MediaDtos.UploadInit;
import vn.com.coreacademy.community.dto.MediaDtos.UploadSummaryView;
import vn.com.coreacademy.community.dto.MediaDtos.UploadView;
import vn.com.coreacademy.community.entity.AssetUploadPart;
import vn.com.coreacademy.community.entity.CommunityAsset;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.mapper.CommunityMediaMapper;
import vn.com.coreacademy.community.repository.CommunityMediaRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.storage.config.S3Properties;
import vn.com.coreacademy.storage.dto.ObjectDownload;
import vn.com.coreacademy.storage.service.MultipartObjectStorage;
import vn.com.coreacademy.storage.service.UploadStaging;

/** Reserve/ACK trong DB; stream S3 ngoài transaction. Hash của từng part bất biến sau lần reserve đầu. */
@Service
@RequiredArgsConstructor
public class CommunityUploadService {
    private final CommunityRepository repository;
    private final CommunityMediaRepository media;
    private final CommunityPolicy policy;
    private final CommunityAssetPolicy assets;
    private final CommunityMediaValidation validation;
    private final CommunityMediaMapper mapper;
    private final CommunityPayload payload;
    private final CommunityProperties properties;
    private final S3Properties s3;
    private final MultipartObjectStorage multipart;
    private final UploadStaging staging;
    private final PlatformTransactionManager transactions;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    public UploadView initiate(UploadInit request) {
        validation.name(request.originalName(), request.mimeType(), request.sizeBytes());
        String hash = payload.hash("community_upload", request);
        UUID token = UUID.randomUUID();
        CommunityAsset snapshot = write(() -> {
            var actor = policy.actor();
            var asset = media.upload(request.requestId(), actor.person().id());
            if (asset == null) {
                asset = new CommunityAsset();
                asset.setOwnerOrgId(actor.person().orgId());
                asset.setUploadedBy(actor.person().id());
                asset.setOriginalName(request.originalName());
                asset.setMimeType(request.mimeType());
                asset.setSizeBytes(request.sizeBytes());
                asset.setChecksumSha256(request.checksumSha256());
                asset.setMediaType(validation.type(request.mimeType()));
                asset.setStatus("uploading");
                asset.setStorageKey("community/" + UUID.randomUUID());
                asset.setUploadId(request.requestId());
                asset.setRequestHash(hash);
                asset.setUploadTotalBytes(request.sizeBytes());
                asset.setUploadedBytes(0L);
                asset.setChunkSizeBytes(CommunityMediaValidation.PART_SIZE);
                asset.setUploadExpiresAt(Instant.now().plus(properties.getUploadTtl()));
                asset.setProcessingAttempts(0);
                asset.setCleanupStatus("none");
                asset.setCleanupAttempts(0);
                repository.save(asset, actor.person().id());
            } else {
                assets.requireOwn(asset, actor);
                if (!hash.equals(asset.getRequestHash())) {
                    throw new BusinessException(CommunityResponseCode.IDEMPOTENCY_CONFLICT);
                }
            }
            if ("uploading".equals(asset.getStatus()) && asset.getS3UploadId() == null) {
                requireOpen(asset);
                if (asset.getProcessingLeaseExpiresAt() != null
                    && asset.getProcessingLeaseExpiresAt().isAfter(Instant.now())) {
                    throw new BusinessException(CommunityResponseCode.UPLOAD_BUSY);
                }
                asset.setProcessingLeaseToken(token);
                asset.setProcessingLeaseExpiresAt(reservationEnd());
                repository.save(asset, actor.person().id());
            }
            repository.flush();
            return asset;
        });
        if (!token.equals(snapshot.getProcessingLeaseToken())) {
            return status(request.requestId());
        }
        String providerId = null;
        try {
            providerId = multipart.initiate(snapshot.getStorageKey());
            String issued = providerId;
            write(() -> {
                var current = own(request.requestId());
                requireOpen(current);
                if (!token.equals(current.getProcessingLeaseToken())
                    || !current.getProcessingLeaseExpiresAt().isAfter(Instant.now())) {
                    throw new BusinessException(CommunityResponseCode.UPLOAD_BUSY);
                }
                current.setS3UploadId(issued);
                current.setProcessingLeaseToken(null);
                current.setProcessingLeaseExpiresAt(null);
                repository.save(current, policy.actor().person().id());
                return null;
            });
        } catch (RuntimeException exception) {
            if (providerId != null) {
                try {
                    multipart.abort(snapshot.getStorageKey(), providerId);
                } catch (RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            try {
                write(() -> {
                    var current = own(request.requestId());
                    if (token.equals(current.getProcessingLeaseToken())) {
                        current.setProcessingLeaseToken(null);
                        current.setProcessingLeaseExpiresAt(null);
                        repository.save(current, policy.actor().person().id());
                    }
                    return null;
                });
            } catch (RuntimeException recovery) {
                exception.addSuppressed(recovery);
            }
            throw exception;
        }
        return status(request.requestId());
    }

    @Transactional(readOnly = true)
    public UploadView status(UUID uploadId) {
        return view(own(uploadId));
    }

    /** Byte limit và digest xác minh trước IO; ACK token ngăn request hết lease ghi đè kết quả mới. */
    public PartAck part(UUID uploadId, int number, String checksum, InputStream input) throws IOException {
        if (number < 1 || checksum == null || !checksum.matches("[0-9a-fA-F]{64}")) {
            throw new BusinessException(CommunityResponseCode.FILE_MISMATCH);
        }
        String hash = checksum.toLowerCase(Locale.ROOT);
        var snapshot = new TransactionTemplate(transactions).execute(tx -> own(uploadId));
        requireOpen(snapshot);
        int count = partCount(snapshot);
        if (number > count || snapshot.getS3UploadId() == null) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        long expected = Math.min(snapshot.getChunkSizeBytes(), snapshot.getUploadTotalBytes()
            - (long) (number - 1) * snapshot.getChunkSizeBytes());
        try (var file = staging.stage(input, expected)) {
            if (file.size() != expected || !hash.equals(file.checksum())) {
                throw new BusinessException(CommunityResponseCode.FILE_MISMATCH);
            }
            UUID token = UUID.randomUUID();
            boolean send = write(() -> {
                var asset = own(uploadId);
                requireOpen(asset);
                var previous = media.parts(asset.getAssetId()).stream()
                    .filter(value -> value.getPartNumber() == number).findFirst();
                var part = previous.orElseGet(AssetUploadPart::new);
                if (previous.isPresent()) {
                    if (!hash.equals(part.getChecksumSha256()) || part.getSizeBytes() != expected) {
                        throw new BusinessException(CommunityResponseCode.FILE_MISMATCH);
                    }
                    if ("uploaded".equals(part.getPartStatus())) {
                        return false;
                    }
                    if (part.getReservationExpiresAt() != null && part.getReservationExpiresAt()
                        .isAfter(Instant.now())) {
                        throw new BusinessException(CommunityResponseCode.UPLOAD_BUSY);
                    }
                } else {
                    part.setAssetId(asset.getAssetId());
                    part.setPartNumber(number);
                    part.setSizeBytes(expected);
                    part.setChecksumSha256(hash);
                }
                part.setPartStatus("reserved");
                part.setReservationToken(token);
                part.setReservationExpiresAt(reservationEnd());
                repository.save(part, policy.actor().person().id());
                repository.flush();
                return true;
            });
            if (send) {
                String etag = multipart.put(snapshot.getStorageKey(), snapshot.getS3UploadId(), number,
                    file.path(), file.size(), file.checksum());
                write(() -> {
                    var asset = own(uploadId);
                    requireOpen(asset);
                    var part = media.parts(asset.getAssetId()).stream()
                        .filter(value -> value.getPartNumber() == number).findFirst().orElseThrow();
                    if (!token.equals(part.getReservationToken())
                        || !part.getReservationExpiresAt().isAfter(Instant.now())) {
                        throw new BusinessException(CommunityResponseCode.UPLOAD_BUSY);
                    }
                    part.setPartStatus("uploaded");
                    part.setEtag(etag);
                    part.setReservationToken(null);
                    part.setReservationExpiresAt(null);
                    repository.save(part, policy.actor().person().id());
                    asset.setUploadedBytes(media.parts(asset.getAssetId()).stream()
                        .filter(value -> "uploaded".equals(value.getPartStatus()))
                        .mapToLong(AssetUploadPart::getSizeBytes).sum());
                    repository.save(asset, policy.actor().person().id());
                    return null;
                });
            }
        } catch (UploadStaging.LimitExceededException exception) {
            throw new BusinessException(CommunityResponseCode.FILE_MISMATCH);
        }
        return new TransactionTemplate(transactions).execute(tx -> {
            var asset = own(uploadId);
            var part = media.parts(asset.getAssetId()).stream().filter(value -> value.getPartNumber() == number)
                .findFirst().orElseThrow();
            return new PartAck(uploadId, asset.getAssetId(), number, part.getSizeBytes(), part.getChecksumSha256(),
                part.getPartStatus(), asset.getUploadedBytes());
        });
    }

    public UploadView complete(UUID uploadId) {
        return write(() -> {
            var asset = own(uploadId);
            if ("failed".equals(asset.getStatus())
                && "COMMUNITY-MEDIA_UNAVAILABLE".equals(asset.getProcessingErrorCode())) {
                asset.setStatus(asset.getUploadCompletedAt() == null ? "completing" : "processing");
                asset.setProcessingAttempts(0);
                asset.setProcessingErrorCode(null);
                asset.setProcessingLeaseToken(null);
                asset.setProcessingLeaseExpiresAt(null);
                asset.setProcessingAvailableAt(Instant.now());
                repository.save(asset, policy.actor().person().id());
                audit.record(policy.actor().person().id(), "COMMUNITY_MEDIA_RETRY", "community_asset",
                    asset.getAssetId().toString(), asset.getOwnerOrgId(), Map.of("status", asset.getStatus()));
            }
            if (Set.of("completing", "processing", "ready", "failed").contains(asset.getStatus())) {
                return view(asset);
            }
            requireOpen(asset);
            List<AssetUploadPart> parts = media.parts(asset.getAssetId());
            if (asset.getS3UploadId() == null || parts.size() != partCount(asset)) {
                throw new BusinessException(CommunityResponseCode.FILE_MISMATCH);
            }
            long total = 0;
            for (int i = 0; i < parts.size(); i++) {
                var part = parts.get(i);
                long expected = Math.min(asset.getChunkSizeBytes(), asset.getUploadTotalBytes() - total);
                if (part.getPartNumber() != i + 1 || !"uploaded".equals(part.getPartStatus())
                    || part.getSizeBytes() != expected || part.getEtag() == null
                        || part.getReservationToken() != null) {
                    throw new BusinessException(CommunityResponseCode.FILE_MISMATCH);
                }
                total += part.getSizeBytes();
            }
            asset.setStatus("completing");
            asset.setProcessingAvailableAt(Instant.now());
            repository.save(asset, policy.actor().person().id());
            return view(asset);
        });
    }

    public UploadView abort(UUID uploadId) {
        var snapshot = write(() -> {
            var asset = own(uploadId);
            if (!Set.of("uploading", "aborted", "expired").contains(asset.getStatus())) {
                throw new BusinessException(CommunityResponseCode.INVALID_STATE);
            }
            asset.setStatus("aborted");
            asset.setProcessingLeaseToken(null);
            asset.setProcessingLeaseExpiresAt(null);
            asset.setCleanupStatus("pending");
            asset.setCleanupAfter(Instant.now());
            repository.save(asset, policy.actor().person().id());
            return asset;
        });
        if (snapshot.getS3UploadId() != null) {
            multipart.abort(snapshot.getStorageKey(), snapshot.getS3UploadId());
        }
        return status(uploadId);
    }

    @Transactional(readOnly = true)
    public ObjectDownload download(Long revisionAssetId) {
        return assets.download(revisionAssetId, policy.actor());
    }

    @Transactional(readOnly = true)
    public PageResponse<UploadSummaryView> list(String status, String mediaType, String q, int page, int size) {
        CommunityPages.check(page, size);
        if (q != null && q.length() > 300) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        return PageResponse.from(media.ownAssets(policy.actor(), status, mediaType, q, page, size)
            .map(value -> new UploadSummaryView(value.getUploadId(), value.getAssetId(), value.getOriginalName(),
                value.getMediaType(), value.getStatus(), value.getUploadTotalBytes(), value.getUploadedBytes(),
                value.getUploadExpiresAt(), value.getProcessingErrorCode(), capabilities(value))));
    }

    private CommunityAsset own(UUID uploadId) {
        var actor = policy.actor();
        var asset = media.upload(uploadId, actor.person().id());
        assets.requireOwn(asset, actor);
        return asset;
    }

    private void requireOpen(CommunityAsset asset) {
        if (!"uploading".equals(asset.getStatus()) || !asset.getUploadExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
    }

    private int partCount(CommunityAsset asset) {
        return Math.toIntExact((asset.getUploadTotalBytes()
            + asset.getChunkSizeBytes() - 1) / asset.getChunkSizeBytes());
    }

    private Instant reservationEnd() {
        return Instant.now().plusSeconds(s3.multipartCallTimeoutSeconds()).plus(properties.getReservationGrace());
    }

    private UploadView view(CommunityAsset asset) {
        Integer retry = Set.of("completing", "processing").contains(asset.getStatus()) ? 3 : null;
        return new UploadView(asset.getUploadId(), asset.getAssetId(), asset.getStatus(), asset.getChunkSizeBytes(),
            asset.getUploadTotalBytes(), asset.getUploadedBytes(), asset.getUploadExpiresAt(),
            media.parts(asset.getAssetId()).stream().map(mapper::part).toList(), asset.getProcessingErrorCode(), retry,
            asset.getOriginalName(), asset.getMediaType(), capabilities(asset));
    }

    private List<String> capabilities(CommunityAsset asset) {
        return "uploading".equals(asset.getStatus()) && asset.getUploadExpiresAt().isAfter(Instant.now())
            ? List.of("uploadPart", "complete", "abort") : List.of("poll");
    }

    private <T> T write(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(tx -> {
            writeLock.acquire();
            return action.get();
        });
    }
}
