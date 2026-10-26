package vn.com.coreacademy.learning.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.config.LearningMediaProperties;
import vn.com.coreacademy.storage.service.ObjectStorage;

/** Nginx auth_request phải nhận 401/403 khi từ chối, không dùng HTTP 200 + ERROR. */
@Service
@RequiredArgsConstructor
public class LearningMediaGatewayService {
    private final LearningAssetService assets;
    private final ObjectStorage storage;

    private final LearningMediaProperties properties;

    public String authorize(String assetId, String blockId, String enrollmentId, String supplied, String method) {
        if (properties.getGatewayKey().getBytes(StandardCharsets.UTF_8).length < 32 || supplied == null ||
            !MessageDigest.isEqual(
            properties.getGatewayKey().getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))
            || !("GET".equals(method) || "HEAD".equals(method))) {
            throw new AccessDeniedException("Trusted media gateway required");
        }
        try {
            var file = assets.download(positive(assetId), positive(blockId), positive(enrollmentId));
            return storage.gatewayUrl(file.key(), "HEAD".equals(method));
        } catch (BusinessException exception) {
            throw new AccessDeniedException("Media unavailable");
        }
    }

    private Long positive(String value) {
        if (value == null) {
            return null;
        }
        try {
            long number = Long.parseLong(value);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException exception) {
            throw new AccessDeniedException("Invalid media context");
        }
        throw new AccessDeniedException("Invalid media context");
    }

}
