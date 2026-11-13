package vn.com.coreacademy.learning.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import vn.com.coreacademy.learning.config.LearningMediaProperties;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetStatus;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.repository.LearningMediaRepository;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.storage.service.MultipartObjectStorage;
import vn.com.coreacademy.storage.service.ObjectStorage;
import vn.com.coreacademy.storage.service.UploadStaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The shared @Scheduled thread can be held for minutes by AI course generation or course indexing. A transcode
 * longer than the processing lease must keep its claim anyway, or its finished result is thrown away.
 */
class LearningMediaWorkerHeartbeatTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LearningRepository repository = mock(LearningRepository.class);
    private final LearningMediaRepository media = mock(LearningMediaRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final LearningAsset asset = new LearningAsset();
    private LearningMediaWorker worker;

    @AfterEach
    void stop() {
        executor.shutdownNow();
        if (worker != null) {
            worker.stopHeartbeats();
        }
    }

    @Test
    void leaseIsRenewedWhileProcessingEvenIfPollIsNeverCalledAgain() throws Exception {
        LearningMediaProperties properties = new LearningMediaProperties();
        properties.setProcessingLease(Duration.ofMillis(400));
        properties.setProcessingHeartbeat(Duration.ofMillis(50));

        asset.setId(7L);
        asset.setUploadId(UUID.randomUUID());
        asset.setUploadedBy("000005");
        asset.setStorageKey("learning/7/source");
        asset.setStatus(AssetStatus.PROCESSING);
        when(repository.require(eq(LearningAsset.class), eq(7L))).thenReturn(asset);
        when(media.pending()).thenReturn(List.of(7L));
        when(media.parts(any())).thenReturn(List.of());

        // The transcode: runs past the lease, then the probe fails so the job ends.
        CountDownLatch transcoding = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(storage.stat("learning/7/source")).thenAnswer(invocation -> {
            transcoding.countDown();
            finish.await(5, TimeUnit.SECONDS);
            throw new IllegalStateException("probe stopped by the test");
        });

        worker = new LearningMediaWorker(properties, repository, media, mock(LearningWriteLock.class), storage,
            mock(MultipartObjectStorage.class), mock(UploadStaging.class), mock(LearningMediaProbe.class),
            mock(PlatformTransactionManager.class), executor);

        worker.poll(); // The only poll: the scheduler thread is busy elsewhere from here on.
        assertThat(transcoding.await(5, TimeUnit.SECONDS)).isTrue();
        UUID claim = asset.getProcessingLeaseId();
        Instant claimedUntil = asset.getProcessingLeaseExpiresAt();

        Thread.sleep(3 * properties.getProcessingLease().toMillis());

        assertThat(asset.getProcessingLeaseId()).isEqualTo(claim);
        assertThat(asset.getProcessingLeaseExpiresAt()).isAfter(Instant.now()).isAfter(claimedUntil);

        finish.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        // The failure write released the claim; no heartbeat renews a job that is over.
        assertThat(asset.getProcessingLeaseId()).isNull();
        Instant released = asset.getProcessingLeaseExpiresAt();
        Thread.sleep(4 * properties.getProcessingHeartbeat().toMillis());
        assertThat(asset.getProcessingLeaseExpiresAt()).isEqualTo(released);
    }
}
