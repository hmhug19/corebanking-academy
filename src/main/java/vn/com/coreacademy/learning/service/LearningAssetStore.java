package vn.com.coreacademy.learning.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.storage.service.ObjectStorage;
import vn.com.coreacademy.storage.service.UploadStaging;

/** Đường upload một request cũng kiểm nội dung như multipart, tránh bypass codec/pageCount. */
@Component
@Slf4j
@RequiredArgsConstructor
public class LearningAssetStore {
    private final UploadStaging staging;
    private final ObjectStorage storage;
    private final LearningMediaProbe probe;

    public record Stored(String key, String name, String mime, long size, String checksum,
                         BigDecimal duration, Integer pages, String playbackKey, Long playbackSize,
                         String playbackChecksum) {
    }

    public Stored store(MultipartFile file, BigDecimal ignoredDuration, Integer ignoredPages) throws IOException {
        if (file.isEmpty() || file.getSize() > LearningLimits.MAX_VIDEO_BYTES) {
            throw new BusinessException(LearningResponseCode.ASSET_INVALID);
        }
        String name = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String key = "learning/" + UUID.randomUUID();
        String playbackKey = null;
        try (InputStream input = file.getInputStream(); var staged = staging.stage(input,
            LearningLimits.MAX_VIDEO_BYTES);
             var checked = probe.check(staged.path(), name, null)) {
            storage.put(key, staged.path(), checked.mime(), staged.checksum());
            Long playbackSize = null;
            String playbackChecksum = null;
            if (checked.playback() != null) {
                playbackKey = key + "/playback";
                try (InputStream rendition = Files.newInputStream(checked.playback());
                     var ready = staging.stage(rendition, LearningLimits.MAX_RENDITION_BYTES)) {
                    storage.put(playbackKey, ready.path(), checked.mime(), ready.checksum());
                    playbackSize = ready.size();
                    playbackChecksum = ready.checksum();
                }
            }
            return new Stored(key, name, checked.mime(), staged.size(), staged.checksum(), checked.duration(),
                checked.pages(), playbackKey, playbackSize, playbackChecksum);
        } catch (IOException | RuntimeException exception) {
            try {
                if (playbackKey != null) {
                    storage.delete(playbackKey);
                }
                storage.delete(key);
            } catch (RuntimeException cleanup) {
                log.error("Media upload cleanup failed; reconcile upload object");
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    public void discard(String key) {
        storage.delete(key);
    }
}
