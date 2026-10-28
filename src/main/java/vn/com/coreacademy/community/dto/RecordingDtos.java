package vn.com.coreacademy.community.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;

public final class RecordingDtos {
    public record Segment(@NotNull @DecimalMin("0") BigDecimal startSeconds,
                            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal endSeconds,
                            @NotBlank @Size(max = 10_000) String text) {
    }

    public record CreateTranscript(@NotNull UUID clientRequestId, @NotBlank @Size(max = 100) String language,
                                    @NotBlank @Size(max = 1_000_000) String bodyText,
                                    @NotNull @Size(min = 1, max = 10_000) List<@NotNull @Valid Segment> segments) {
    }

    public record UpdateTranscript(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max = 100) String language,
                                    @NotBlank @Size(max = 1_000_000) String bodyText,
                                    @NotNull @Size(min = 1, max = 10_000) List<@NotNull @Valid Segment> segments) {
    }

    public record TranscriptView(Long transcriptId, Long assetId, int versionNo, String language, String bodyText,
                                  List<Segment> segments, String status, Instant publishedAt, Long version,
                                  Instant createTime, Instant updateTime, List<String> capabilities) {
    }

    public record RecordingCard(@JsonUnwrapped ContentCard content, Instant heldAt, JsonNode speakers,
                                 BigDecimal durationSeconds, Long primaryVideoRevisionAssetId, boolean hasTranscript) {
    }

    private RecordingDtos() {
    }
}
