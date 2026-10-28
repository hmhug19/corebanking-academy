package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class JobDtos {
    public record Reindex(@NotNull UUID requestId, @Size(max = 500) List<@NotNull @Positive Long> contentIds,
                           @Positive Long guildId) {
    }

    public record Reconcile(@NotNull UUID requestId, @Size(max = 500) List<@NotBlank @Size(max = 20) String> userIds,
                             Instant from, Instant to) {
    }

    public record JobView(Long eventId, String status, long processedCount, long failedCount, String errorCode) {
    }

    private JobDtos() {
    }
}
