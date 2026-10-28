package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class AiDtos {
    public record History(@NotNull @Pattern(regexp = "user|assistant") String role,
                            @NotBlank @Size(max = 4_000) String content) {
    }

    public record AnswerRequest(@NotNull UUID requestId, @NotBlank @Size(max = 2_000) String question,
                                  @NotNull @Size(min = 1, max = 3)
                                  List<@NotNull @Pattern(regexp = "community|learning|knowledge") String> sourceTypes,
                                  @Positive Long guildId, @Size(max = 10) List<@NotNull @Valid History> history) {
    }

    public record Citation(int citationNo, String sourceType, Long sourceId, Long revisionId, String title,
                            String url, Map<String, Object> locator) {
    }

    public record AIAnswerView(Long turnId, String status, String answer, List<Citation> citations,
        boolean resultRetained) {
    }

    public record AISourceView(Long sourceId, int citationNo, boolean usedInAnswer, Citation citation) {
    }

    public record Feedback(@NotNull @Min(0) Long expectedVersion, @NotNull Boolean helpful,
                             @Pattern(regexp = "irrelevant|incorrect|outdated|missing_source|other") String reasonCode,
                             @Size(max = 2_000) String note) {
    }

    public record AIFeedbackView(Long feedbackId, Long turnId, String userId, boolean helpful, String reasonCode,
                                  String note, Long version, Instant createTime, Instant updateTime) {
    }

    private AiDtos() {
    }
}
