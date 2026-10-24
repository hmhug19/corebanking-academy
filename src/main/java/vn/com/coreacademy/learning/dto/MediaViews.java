package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MediaViews {
    private MediaViews() {
    }

    public record Part(int partNumber, long sizeBytes, String checksumSha256) {
    }

    public record Upload(UUID uploadId, Long assetId, String status, long totalBytes, long uploadedBytes,
                         int partSize, Instant expiresAt, List<Part> parts, String errorCode, String errorMessage,
                         String mimeType, BigDecimal durationSeconds, Integer pageCount, Instant mediaCheckedAt) {
    }

    public record Session(UUID sessionId, long sessionFence, Instant leaseExpiresAt, long lastAcceptedSequence,
                          Long enrollmentId, Long blockId, Long assetId, long expectedVersion,
                          LearningViews.BlockProgressView resume) {
    }

    public record Receipt(UUID sessionId, long sessionFence, long lastAcceptedSequence,
                          String leaseState, Instant leaseExpiresAt) {
    }

    public record Asset(Long id, Long ownerOrgId, String originalName, String mimeType, long sizeBytes,
                        String mediaType, String status, Instant createTime) {
    }
}
