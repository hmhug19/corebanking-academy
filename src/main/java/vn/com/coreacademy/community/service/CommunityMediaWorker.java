package vn.com.coreacademy.community.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.config.CommunityProperties;
import vn.com.coreacademy.community.entity.CommunityAsset;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityMediaRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.storage.config.S3Properties;
import vn.com.coreacademy.storage.service.MultipartObjectStorage;
import vn.com.coreacademy.storage.service.ObjectStorage;
import vn.com.coreacademy.storage.service.UploadStaging;

/** IO/probe ngoài transaction; mọi ACK kết quả kiểm token và hạn lease trước khi ghi trạng thái ready. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommunityMediaWorker {
    private final CommunityProperties properties;
    private final S3Properties s3;
    private final CommunityRepository repository;
    private final CommunityMediaRepository media;
    private final CommunityMediaValidation validation;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;
    private final PlatformTransactionManager transactions;
    private final ObjectStorage storage;
    private final MultipartObjectStorage multipart;
    private final UploadStaging staging;
    private final ExecutorService communityMediaExecutor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<Job> currentJob = new AtomicReference<>();
    private final AtomicLong heartbeatAfter = new AtomicLong();

    private record Job(CommunityAsset asset, UUID token, List<MultipartObjectStorage.CommittedPart> parts) {
    }

    @PostConstruct
    public void verifyWorkerActor() {
        if (properties.isWorkerEnabled() || properties.isCleanupEnabled()) {
            // Cấu hình sai phải fail startup; không âm thầm mượn uploader hay tự seed tài khoản kỹ thuật.
            identity.activePerson(properties.getWorkerUserId());
        }
    }

    @Scheduled(fixedDelayString = "#{@communityProperties.pollMs}",
        initialDelayString = "#{@communityProperties.initialDelay.toMillis()}")
    public void poll() {
        if (!properties.isWorkerEnabled() && !properties.isCleanupEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            heartbeat();
            return;
        }
        try {
            communityMediaExecutor.execute(() -> {
                try {
                    Job job = properties.isWorkerEnabled() ? claim() : null;
                    if (job != null) {
                        currentJob.set(job);
                        heartbeatAfter.set(System.currentTimeMillis() + properties.getHeartbeat().toMillis());
                        process(job);
                    } else if (properties.isCleanupEnabled()) {
                        cleanup();
                    }
                } catch (RuntimeException exception) {
                    log.error("Community media cycle failed type={}", exception.getClass().getSimpleName());
                } finally {
                    currentJob.set(null);
                    running.set(false);
                }
            });
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    private Job claim() {
        return write(() -> {
            for (var expired : media.expired()) {
                expired.setStatus("expired");
                expired.setCleanupStatus("pending");
                expired.setCleanupAfter(Instant.now());
                save(expired);
            }
            var pending = media.pending();
            if (pending.isEmpty()) {
                return null;
            }
            var asset = pending.get(0);
            if (asset.getProcessingAttempts() >= 3) {
                asset.setStatus("failed");
                asset.setProcessingErrorCode("COMMUNITY-MEDIA_UNAVAILABLE");
                save(asset);
                return null;
            }
            UUID token = UUID.randomUUID();
            asset.setProcessingLeaseToken(token);
            asset.setProcessingLeaseExpiresAt(Instant.now().plus(properties.getLease()));
            asset.setProcessingAttempts(asset.getProcessingAttempts() + 1);
            save(asset);
            var parts = media.parts(asset.getAssetId()).stream().map(part -> new MultipartObjectStorage.CommittedPart(
                part.getPartNumber(), part.getEtag(), part.getSizeBytes())).toList();
            repository.flush();
            return new Job(asset, token, parts);
        });
    }

    private void heartbeat() {
        Job job = currentJob.get();
        if (job == null || System.currentTimeMillis() < heartbeatAfter.get()) {
            return;
        }
        write(() -> {
            var asset = current(job);
            if (asset != null) {
                asset.setProcessingLeaseExpiresAt(Instant.now().plus(properties.getLease()));
                save(asset);
            }
            return null;
        });
        heartbeatAfter.set(System.currentTimeMillis() + properties.getHeartbeat().toMillis());
    }

    private void process(Job job) {
        var snapshot = job.asset();
        String renditionKey = null;
        try {
            // Complete mất ACK có thể đã tạo object; byte/hash được kiểm lại trước ready, không complete mù.
            if (!storage.exists(snapshot.getStorageKey())) {
                multipart.complete(snapshot.getStorageKey(), snapshot.getS3UploadId(), job.parts());
            }
            var info = storage.stat(snapshot.getStorageKey());
            if (info.size() != snapshot.getUploadTotalBytes()) {
                throw new BusinessException(CommunityResponseCode.FILE_MISMATCH);
            }
            boolean active = write(() -> {
                var asset = current(job);
                if (asset == null) {
                    return false;
                }
                asset.setStatus("processing");
                asset.setUploadCompletedAt(asset.getUploadCompletedAt() == null ? Instant.now()
                    : asset.getUploadCompletedAt());
                save(asset);
                return true;
            });
            if (!active) {
                return;
            }
            try (InputStream input = storage.read(snapshot.getStorageKey(), 0, info.size(), info.etag());
                 var original = staging.stage(input, snapshot.getUploadTotalBytes())) {
                if (!snapshot.getChecksumSha256().equals(original.checksum())) {
                    throw new BusinessException(CommunityResponseCode.FILE_MISMATCH);
                }
                try (var checked = validation.check(original.path(), snapshot.getOriginalName(),
                    snapshot.getMimeType())) {
                    Long renditionSize = null;
                    String renditionHash = null;
                    if (checked.playback() != null) {
                        renditionKey = "community/" + snapshot.getAssetId() + "/" + job.token();
                        try (InputStream inputRendition = Files.newInputStream(checked.playback());
                             var rendition = staging.stage(inputRendition, 550_000_000)) {
                            storage.put(renditionKey, rendition.path(), checked.mime(), rendition.checksum());
                            renditionSize = rendition.size();
                            renditionHash = rendition.checksum();
                        }
                    }
                    String key = renditionKey;
                    Long size = renditionSize;
                    String hash = renditionHash;
                    boolean committed = write(() -> {
                        var asset = current(job);
                        if (asset == null) {
                            return false;
                        }
                        asset.setStatus("ready");
                        asset.setDurationSeconds(checked.duration());
                        asset.setPageCount(checked.pages());
                        asset.setMediaCheckedAt(Instant.now());
                        asset.setPlaybackKey(key);
                        asset.setPlaybackSizeBytes(size);
                        asset.setPlaybackChecksumSha256(hash);
                        asset.setPlaybackMimeType(key == null ? null : checked.mime());
                        asset.setProcessingLeaseToken(null);
                        asset.setProcessingLeaseExpiresAt(null);
                        asset.setProcessingErrorCode(null);
                        asset.setCleanupAfter(Instant.now().plus(properties.getOrphanRetention()));
                        save(asset);
                        return true;
                    });
                    if (committed) {
                        renditionKey = null;
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.error("Community media processing failed assetId={} type={}", snapshot.getAssetId(),
                exception.getClass().getSimpleName());
            write(() -> {
                var asset = current(job);
                if (asset != null) {
                    boolean invalid = exception instanceof BusinessException;
                    asset.setStatus(invalid || asset.getProcessingAttempts() >= 3 ? "failed" : asset.getStatus());
                    asset.setProcessingErrorCode(invalid ? ((BusinessException) exception).getResponseCode().getCode()
                        : "COMMUNITY-MEDIA_UNAVAILABLE");
                    asset.setProcessingAvailableAt(Instant.now().plus(asset.getProcessingAttempts() == 1
                        ? properties.getRetryDelay() : properties.getSecondRetryDelay()));
                    asset.setProcessingLeaseToken(null);
                    asset.setProcessingLeaseExpiresAt(null);
                    save(asset);
                }
                return null;
            });
        } finally {
            if (renditionKey != null) {
                try {
                    storage.delete(renditionKey);
                } catch (RuntimeException exception) {
                    log.error("Unpublished Community rendition cleanup failed assetId={}", snapshot.getAssetId());
                }
            }
        }
    }

    /** Lease cleanup chặn attach mới trong lúc IO; tham chiếu ở mọi revision, kể cả lịch sử xóa mềm, đều giữ tệp. */
    private void cleanup() {
        UUID token = UUID.randomUUID();
        var snapshot = write(() -> {
            var values = media.cleanupCandidates(Instant.now().minus(properties.getOrphanRetention()));
            if (values.isEmpty()) {
                return null;
            }
            var asset = values.get(0);
            if (asset.getCleanupAttempts() >= 3 || media.hasReferences(asset.getAssetId())) {
                return null;
            }
            asset.setCleanupStatus("deleting");
            asset.setCleanupLeaseToken(token);
            // Hai IO xóa có thể dài hơn lease xử lý; không cấp lại trước tổng timeout S3 + grace.
            asset.setCleanupLeaseExpiresAt(Instant.now().plusSeconds(2L * s3.callTimeoutSeconds()
                + s3.multipartCallTimeoutSeconds()).plus(properties.getReservationGrace()));
            asset.setCleanupAttempts(asset.getCleanupAttempts() + 1);
            save(asset);
            return asset;
        });
        if (snapshot == null) {
            return;
        }
        try {
            if (snapshot.getS3UploadId() != null) {
                multipart.abort(snapshot.getStorageKey(), snapshot.getS3UploadId());
            }
            storage.delete(snapshot.getStorageKey());
            if (snapshot.getPlaybackKey() != null) {
                storage.delete(snapshot.getPlaybackKey());
            }
            write(() -> {
                var asset = repository.require(CommunityAsset.class, snapshot.getAssetId());
                if (token.equals(asset.getCleanupLeaseToken()) && asset.getCleanupLeaseExpiresAt()
                    .isAfter(Instant.now())
                    && !media.hasReferences(asset.getAssetId())) {
                    asset.setCleanupStatus("deleted");
                    asset.setDeletedAt(Instant.now());
                    asset.setDeletedBy(properties.getWorkerUserId());
                    asset.setCleanupLeaseToken(null);
                    asset.setCleanupLeaseExpiresAt(null);
                    save(asset);
                }
                return null;
            });
        } catch (RuntimeException exception) {
            write(() -> {
                var asset = repository.require(CommunityAsset.class, snapshot.getAssetId());
                if (token.equals(asset.getCleanupLeaseToken())) {
                    asset.setCleanupStatus("pending");
                    asset.setCleanupLastErrorCode("COMMUNITY-CLEANUP_UNAVAILABLE");
                    asset.setCleanupLeaseToken(null);
                    asset.setCleanupLeaseExpiresAt(null);
                    asset.setCleanupAfter(Instant.now().plus(properties.getSecondRetryDelay()));
                    save(asset);
                }
                return null;
            });
        }
    }

    private CommunityAsset current(Job job) {
        var asset = repository.require(CommunityAsset.class, job.asset().getAssetId());
        return job.token().equals(asset.getProcessingLeaseToken()) && asset.getDeletedAt() == null
            && asset.getProcessingLeaseExpiresAt() != null && asset.getProcessingLeaseExpiresAt().isAfter(Instant.now())
            ? asset : null;
    }

    private void save(CommunityAsset asset) {
        repository.save(asset, properties.getWorkerUserId());
    }

    private <T> T write(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(tx -> {
            writeLock.acquire();
            identity.activePerson(properties.getWorkerUserId());
            return action.get();
        });
    }
}
