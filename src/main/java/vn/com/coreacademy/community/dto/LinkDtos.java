package vn.com.coreacademy.community.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class LinkDtos {
    public record LinkInput(@NotNull @Pattern(regexp = "related|summarizes|supersedes|learning_reference")
                             String relationType,
                             @Positive Long targetContentId, @Positive Long targetCourseId,
                             @Positive Long targetKnowledgeDocumentId) {
    }

    public record Links(@NotNull @Min(0) Long expectedContentVersion,
                         @NotNull @Size(max = 100) List<@NotNull @Valid LinkInput> links) {
    }

    public record LinkView(Long linkId, Long sourceContentId, String relationType, String targetSourceType,
                            Long targetId, Long targetRevisionId, String targetTitle, String targetUrl) {
    }

    public record LinkResult(Long contentVersion, List<LinkView> items) {
    }

    private LinkDtos() {
    }
}
