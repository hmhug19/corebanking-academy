package vn.com.coreacademy.learning.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.config.LearningMediaProperties;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.ProcessingError;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.LearningMediaRepository;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.storage.service.MultipartObjectStorage;
import vn.com.coreacademy.storage.service.ObjectStorage;
import vn.com.coreacademy.storage.service.UploadStaging;

/** Xử lý file đã complete; không tự abort upload hoặc xóa media S3 theo retention. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LearningMediaWorker {
    private final LearningMediaProperties properties;
    private final LearningRepository repository;
    private final LearningMediaRepository media;
    private final LearningWriteLock lock;
    private final ObjectStorage storage;
    private final MultipartObjectStorage multipart;
    private final UploadStaging staging;
    private final LearningMediaProbe probe;
    private final PlatformTransactionManager transactions;
    private final ExecutorService learningMediaExecutor;
    /**
     * Renews the lease of the file being processed. Not the shared @Scheduled thread: AI course generation and
     * course indexing hold that one for minutes, the lease lapsed and a finished transcode was thrown away. Not a
     * bean either: a ScheduledExecutorService bean would replace Spring Boot's scheduler for every @Scheduled.
     */
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "learning-media-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    private record Job(LearningAsset asset, UUID claim, List<MultipartObjectStorage.CommittedPart> parts) {
    }

    @Scheduled(fixedDelayString = "#{@learningMediaProperties.pollMs}",
        initialDelayString = "#{@learningMediaProperties.initialDelay.toMillis()}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            learningMediaExecutor.execute(() -> {
                try {
                    Job job = claim();
                    if (job != null) {
                        long every = properties.getProcessingHeartbeat().toMillis();
                        ScheduledFuture<?> beat = heartbeats.scheduleWithFixedDelay(
                            () -> heartbeat(job), every, every, TimeUnit.MILLISECONDS);
                        try {
                            process(job);
                        } finally {
                            beat.cancel(false);
                        }
                    }
                } catch (RuntimeException exception) {
                    log.error("Media worker cycle failed type={}", exception.getClass().getSimpleName());
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    @PreDestroy
    void stopHeartbeats() {
        heartbeats.shutdownNow();
    }

    /** Keeps the claim while `process` runs; a lease that lapsed is not taken back (another claim may own it). */
    private void heartbeat(Job job) {
        try {
            write(() -> {
                LearningAsset asset = current(job);
                if (asset == null) {
                    log.warn("Media lease lost while processing assetId={}", job.asset().getId());
                } else {
                    asset.setProcessingLeaseExpiresAt(Instant.now().plus(properties.getProcessingLease()));
                    repository.save(asset, asset.getUploadedBy());
                }
                return null;
            });
        } catch (RuntimeException exception) {
            // A throwing task would stop every later heartbeat of this job.
            log.error("Media heartbeat failed assetId={} type={}", job.asset().getId(),
                exception.getClass().getSimpleName());
        }
    }

    private Job claim() {
        return write(() -> {
            media.expireUploads();
            var pending = media.pending();
            if (pending.isEmpty()) {
                return null;
            }
            LearningAsset asset = repository.require(LearningAsset.class, pending.get(0));
            if (asset.getProcessingAttempts() >= properties.getMaxProcessingAttempts()) {
                asset.setStatus(AssetStatus.FAILED);
                asset.setProcessingErrorCode(ProcessingError.UNAVAILABLE);
                asset.setProcessingError("Không thể xử lý tài nguyên sau nhiều lần thử. Vui lòng liên hệ quản trị.");
                return null;
            }
            UUID claim = UUID.randomUUID();
            asset.setProcessingAttempts(asset.getProcessingAttempts() + 1);
            asset.setProcessingLeaseId(claim);
            asset.setProcessingLeaseExpiresAt(Instant.now().plus(properties.getProcessingLease()));
            repository.save(asset, asset.getUploadedBy());
            var parts = media.parts(asset.getUploadId()).stream()
                .map(p -> new MultipartObjectStorage.CommittedPart(p.getPartNumber(), p.getEtag(), p.getSizeBytes()))
                .toList();
            repository.flush();
            return new Job(asset, claim, parts);
        });
    }

    private void process(Job job) {
        LearningAsset source = job.asset();
        String stage = "complete_upload";
        try {
            if (AssetStatus.COMPLETING.equals(source.getStatus())) {
                if (!storage.exists(source.getStorageKey())) {
                    multipart.complete(source.getStorageKey(), source.getS3UploadId(), job.parts());
                }
                write(() -> {
                    LearningAsset asset = current(job);
                    if (asset != null) {
                        asset.setStatus(AssetStatus.PROCESSING);
                        asset.setUploadCompletedAt(Instant.now());
                        repository.save(asset, asset.getUploadedBy());
                    }
                    return null;
                });
            }
            stage = "read_original";
            var object = storage.stat(source.getStorageKey());
            if (object.size() != source.getUploadTotalBytes()) {
                throw new BusinessException(LearningResponseCode.UPLOAD_FILE_MISMATCH);
            }
            try (InputStream input = storage.read(source.getStorageKey(), 0, object.size(), object.etag());
                 var original = staging.stage(input, source.getUploadTotalBytes())) {
                if (!source.getUploadExpectedSha256().equals(original.checksum())) {
                    throw new BusinessException(LearningResponseCode.UPLOAD_FILE_MISMATCH);
                }
                stage = "probe_and_prepare";
                try (var checked = probe.check(original.path(), source.getOriginalName(), source.getMimeType())) {
                    String playbackKey = null;
                    Long playbackSize = null;
                    String playbackChecksum = null;
                    if (checked.playback() != null) {
                        stage = "store_playback";
                        playbackKey = "learning/" + source.getId() + "/" + job.claim();
                        try (InputStream rendition = Files.newInputStream(checked.playback());
                             var ready = staging.stage(rendition, LearningLimits.MAX_RENDITION_BYTES)) {
                            storage.put(playbackKey, ready.path(), checked.mime(), ready.checksum());
                            playbackSize = ready.size();
                            playbackChecksum = ready.checksum();
                        }
                    }
                    String finalKey = playbackKey;
                    Long finalSize = playbackSize;
                    String finalChecksum = playbackChecksum;
                    stage = "save_metadata";
                    write(() -> {
                        LearningAsset asset = current(job);
                        if (asset == null) {
                            log.warn("Stale media result ignored assetId={}; reconcile unpublished rendition",
                                source.getId());
                            return null;
                        }
                        asset.setMimeType(checked.mime());
                        asset.setDurationSeconds(checked.duration());
                        asset.setPageCount(checked.pages());
                        asset.setPageCountCheckedAt(checked.pages() == null ? null : Instant.now());
                        asset.setChecksumSha256(original.checksum());
                        asset.setSizeBytes(original.size());
                        asset.setUploadedBytes(original.size());
                        asset.setPlaybackKey(finalKey);
                        asset.setPlaybackSizeBytes(finalSize);
                        asset.setPlaybackChecksumSha256(finalChecksum);
                        asset.setPlaybackMimeType(finalKey == null ? null : checked.mime());
                        asset.setMediaCheckedAt(Instant.now());
                        asset.setUploadCompletedAt(asset.getUploadCompletedAt() == null
                            ? Instant.now() : asset.getUploadCompletedAt());
                        asset.setProcessingLeaseId(null);
                        asset.setProcessingLeaseExpiresAt(null);
                        asset.setProcessingError(null);
                        asset.setProcessingErrorCode(null);
                        asset.setStatus(AssetStatus.READY);
                        repository.save(asset, asset.getUploadedBy());
                        return null;
                    });
                }
            }
        } catch (IOException | RuntimeException exception) {
            log.error("Media processing failed assetId={} stage={} type={}", source.getId(), stage,
                exception.getClass().getSimpleName());
            write(() -> {
                LearningAsset asset = current(job);
                if (asset != null) {
                    boolean invalid = exception instanceof BusinessException;
                    asset.setStatus(invalid || asset.getProcessingAttempts() >= properties.getMaxProcessingAttempts() ?
                        AssetStatus.FAILED : asset.getStatus());
                    asset.setProcessingErrorCode(invalid ? ((BusinessException) exception).getResponseCode().getCode()
                        : ProcessingError.UNAVAILABLE);
                    asset.setProcessingError(invalid ? ((BusinessException) exception).getResponseCode().getMessage()
                        : "Dịch vụ xử lý media chưa sẵn sàng. Hệ thống sẽ thử lại có giới hạn.");
                    asset.setProcessingLeaseId(null);
                    asset.setProcessingLeaseExpiresAt(Instant.now().plus(properties.getRetryDelay()));
                    repository.save(asset, asset.getUploadedBy());
                }
                return null;
            });
        }
    }

    private LearningAsset current(Job job) {
        LearningAsset asset = repository.require(LearningAsset.class, job.asset().getId());
        return job.claim().equals(asset.getProcessingLeaseId()) && !Boolean.TRUE.equals(asset.getIsDeleted())
            && asset.getProcessingLeaseExpiresAt().isAfter(Instant.now()) ? asset : null;
    }

    private <T> T write(Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(tx -> {
            lock.acquire();
            return action.get();
        });
    }
}
