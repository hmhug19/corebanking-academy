package vn.com.coreacademy.learning.dto;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import vn.com.coreacademy.learning.constants.LearningLimits;

public final class MediaRequests {
    private MediaRequests() {
    }

    public record Upload(@NotNull UUID requestId, @NotNull @Positive Long ownerOrgId,
                         @NotBlank @Size(max = 255) String originalName,
                         @NotBlank @Size(max = 100) String mimeType,
                         @Min(1) @Max(LearningLimits.MAX_VIDEO_BYTES) long sizeBytes,
                         @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String checksumSha256) {
    }

    public record Session(@NotNull @Positive Long enrollmentId, @NotNull @Positive Long blockId,
                          UUID previousSessionId, @Min(1) Long previousFence, boolean takeover) {
    }
}
