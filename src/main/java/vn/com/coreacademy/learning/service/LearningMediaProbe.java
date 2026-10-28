package vn.com.coreacademy.learning.service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.http.MediaType;
import vn.com.coreacademy.learning.constants.LearningCodes.MediaKind;
import vn.com.coreacademy.learning.constants.LearningCodes.MediaMime;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.config.LearningMediaProperties;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.storage.media.MediaProbe;

/** Adapter giữ policy/exception Learning; probe bytes dùng chung không biết ACL hoặc repository nghiệp vụ. */
@Component
public class LearningMediaProbe {
    private final MediaProbe probe;

    public LearningMediaProbe(LearningMediaProperties properties, ObjectMapper json) {
        probe = new MediaProbe(new MediaProbe.Settings(properties.getFfprobe(), properties.getFfmpeg(),
            properties.getProbeTimeout()), json);
    }

    public static String type(String mime) {
        return mime.startsWith(MediaMime.VIDEO_PREFIX) ? MediaKind.VIDEO : mime.startsWith(MediaMime.IMAGE_PREFIX) ?
            MediaKind.IMAGE
            : MediaType.APPLICATION_PDF_VALUE.equals(mime) ? MediaKind.DOCUMENT : MediaKind.ATTACHMENT;
    }

    public static void validateName(String name, String mime, long size) {
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
            || name.codePoints().anyMatch(Character::isISOControl) || size <= 0 || size > limit(mime)) {
            throw new BusinessException(LearningResponseCode.ASSET_INVALID);
        }
    }

    public static long limit(String mime) {
        if (mime == null) {
            return 0;
        }
        if (mime.equals(MediaMime.VIDEO_MP4) || mime.equals(MediaMime.VIDEO_WEBM)) {
            return LearningLimits.MAX_VIDEO_BYTES;
        }
        if (mime.equals(MediaType.APPLICATION_PDF_VALUE)) {
            return LearningLimits.MAX_PDF_BYTES;
        }
        if (List.of(MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE, MediaMime.IMAGE_WEBP).contains(mime)) {
            return LearningLimits.MAX_IMAGE_BYTES;
        }
        return mime.startsWith(MediaMime.OFFICE_PREFIX) ? LearningLimits.MAX_ATTACHMENT_BYTES : 0;
    }

    public MediaProbe.Checked check(Path file, String name, String expectedMime) throws IOException {
        try {
            String actualMime = expectedMime == null ? probe.detect(file, name) : expectedMime;
            validateName(name, actualMime, Files.size(file));
            return probe.check(file, name, expectedMime, limit(actualMime), LearningLimits.MAX_MEDIA_DURATION_SECONDS);
        } catch (MediaProbe.Rejected exception) {
            throw new BusinessException(LearningResponseCode.valueOf(exception.getMessage()));
        }
    }

    public String detect(Path path, String name) throws IOException {
        try {
            return probe.detect(path, name);
        } catch (MediaProbe.Rejected exception) {
            throw new BusinessException(LearningResponseCode.valueOf(exception.getMessage()));
        }
    }
}
