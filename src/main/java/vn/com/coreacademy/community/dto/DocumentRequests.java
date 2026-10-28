package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.community.dto.ContentRequests.AssetInput;
import vn.com.coreacademy.community.dto.ContentRequests.CreateContent;

public final class DocumentRequests {
    public interface CreateBody {
        UUID clientRequestId();
        String title();
        String bodyMarkdown();
        String summary();
        String visibility();
        List<Long> tagIds();
        JsonNode context();
        List<AssetInput> assets();

        default CreateContent content(Long guildId) {
            return new CreateContent(clientRequestId(), title(), bodyMarkdown(), summary(), visibility(),
                "guild".equals(visibility()) ? guildId : null, tagIds(), context(), assets());
        }
    }

    public record CreateWiki(
        @NotNull UUID clientRequestId,
        @NotNull @Size(max = 255) String title,
        @NotNull @Size(max = 100_000) String bodyMarkdown,
        @Size(max = 2_000) String summary,
        @NotNull @Pattern(regexp = "organization|guild|restricted") String visibility,
        @Size(max = 10) List<@NotNull @Positive Long> tagIds,
        JsonNode context,
        @Valid @Size(max = 20) List<AssetInput> assets,
        @Positive Long parentPageId,
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @Min(0) int sortNo) implements CreateBody {
    }

    public record CreateRadar(
        @NotNull UUID clientRequestId,
        @NotNull @Size(max = 255) String title,
        @NotNull @Size(max = 100_000) String bodyMarkdown,
        @Size(max = 2_000) String summary,
        @NotNull @Pattern(regexp = "organization|guild|restricted") String visibility,
        @Size(max = 10) List<@NotNull @Positive Long> tagIds,
        JsonNode context,
        @Valid @Size(max = 20) List<AssetInput> assets) implements CreateBody {
    }

    public record CreateRecording(
        @NotNull UUID clientRequestId,
        @NotNull @Size(max = 255) String title,
        @NotNull @Size(max = 100_000) String bodyMarkdown,
        @Size(max = 2_000) String summary,
        @NotNull @Pattern(regexp = "organization|guild|restricted") String visibility,
        @Size(max = 10) List<@NotNull @Positive Long> tagIds,
        JsonNode context,
        @Valid @Size(max = 20) List<AssetInput> assets,
        @Positive Long primaryGuildId) implements CreateBody {
    }

    public record CreateReview(
        @NotNull UUID clientRequestId,
        @NotNull @Size(max = 255) String title,
        @NotNull @Size(max = 100_000) String bodyMarkdown,
        @Size(max = 2_000) String summary,
        @NotNull @Pattern(regexp = "organization|guild|restricted") String visibility,
        @Size(max = 10) List<@NotNull @Positive Long> tagIds,
        JsonNode context,
        @Valid @Size(max = 20) List<AssetInput> assets,
        @Positive Long primaryGuildId,
        Instant dueAt) implements CreateBody {
    }

    public record FollowUpReview(
        @NotNull UUID clientRequestId,
        @NotNull @Size(max = 255) String title,
        @NotNull @Size(max = 100_000) String bodyMarkdown,
        @Size(max = 2_000) String summary,
        @NotNull @Pattern(regexp = "organization|guild|restricted") String visibility,
        @Size(max = 10) List<@NotNull @Positive Long> tagIds,
        JsonNode context,
        @Valid @Size(max = 20) List<AssetInput> assets,
        @Positive Long primaryGuildId,
        Instant dueAt,
        @NotNull @Positive Long sourceRevisionId) implements CreateBody {
    }

    public record CreateKnowledgeRequest(
        @NotNull UUID clientRequestId,
        @NotNull @Size(max = 255) String title,
        @NotNull @Size(max = 100_000) String bodyMarkdown,
        @Size(max = 2_000) String summary,
        @NotNull @Pattern(regexp = "organization|guild|restricted") String visibility,
        @Size(max = 10) List<@NotNull @Positive Long> tagIds,
        JsonNode context,
        @Valid @Size(max = 20) List<AssetInput> assets,
        @Positive Long primaryGuildId,
        Instant dueAt) implements CreateBody {
    }

    public record CreatePostmortem(
        @NotNull UUID clientRequestId,
        @NotNull @Size(max = 255) String title,
        @NotNull @Size(max = 100_000) String bodyMarkdown,
        @Size(max = 2_000) String summary,
        @NotNull @Pattern(regexp = "organization|guild|restricted") String visibility,
        @Size(max = 10) List<@NotNull @Positive Long> tagIds,
        JsonNode context,
        @Valid @Size(max = 20) List<AssetInput> assets,
        @Positive Long primaryGuildId) implements CreateBody {
    }

    private DocumentRequests() {
    }
}
