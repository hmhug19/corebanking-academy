package vn.com.coreacademy.community.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class MediaDtos {
    public record UploadInit(@NotNull UUID requestId, @NotBlank @Size(max = 255) String originalName,
                              @NotBlank @Size(max = 100) String mimeType, @NotNull @Positive Long sizeBytes,
                              @NotNull @Pattern(regexp = "[0-9a-f]{64}") String checksumSha256) {
        public UploadInit {
            checksumSha256 = checksumSha256 == null ? null : checksumSha256.toLowerCase(Locale.ROOT);
        }
    }

    public record PartView(int partNumber, long sizeBytes, String checksumSha256, String status) {
    }

    public record UploadView(UUID uploadId, Long assetId, String status, int partSize, long totalBytes,
                              long uploadedBytes, Instant expiresAt, List<PartView> parts,
                              String processingErrorCode, Integer retryAfterSeconds, String originalName,
                              String mediaType, List<String> capabilities) {
    }

    public record UploadSummaryView(UUID uploadId, Long assetId, String originalName, String mediaType, String status,
                                     long totalBytes, long uploadedBytes, Instant expiresAt, String processingErrorCode,
                                     List<String> capabilities) {
    }

    public record PartAck(UUID uploadId, Long assetId, int partNumber, long sizeBytes, String checksumSha256,
                           String status, long uploadedBytes) {
    }

    public record AssetView(Long assetId, Long revisionAssetId, String originalName, String mimeType,
                             long sizeBytes, String mediaType, String status, BigDecimal durationSeconds,
                             Integer pageCount, Instant createTime) {
    }

    private MediaDtos() {
    }
}
