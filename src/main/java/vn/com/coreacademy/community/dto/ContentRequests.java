package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.community.constants.CommunityLimits;

public final class ContentRequests {
    public record AssetInput(
        @NotNull @Positive Long assetId,
        @NotBlank @Pattern(regexp = "inline|attachment|cover|review_artifact|primary_video|inline_image|design_file")
        String usage,
        @Size(max = 5_000) String caption,
        @Size(max = 255) String altText,
        @Min(0) int sortNo) {
    }

    @Schema(name = "CommunityCreateContent")
    public record CreateContent(
        @NotNull UUID clientRequestId,
        @NotNull @Size(max = CommunityLimits.TITLE) String title,
        @NotNull @Size(max = CommunityLimits.BODY) String bodyMarkdown,
        @Size(max = CommunityLimits.SUMMARY) String summary,
        @Pattern(regexp = "organization|guild|restricted") String visibility,
        @Positive Long primaryGuildId,
        @Size(max = CommunityLimits.TAGS) List<@NotNull @Positive Long> tagIds,
        JsonNode context,
        @Valid @Size(max = CommunityLimits.ASSETS) List<AssetInput> assets) {
    }

    public record SaveDraft(
        @NotNull @Min(0) Long expectedContentVersion,
        @NotNull @Min(0) Long expectedRevisionVersion,
        @NotNull @Size(max = CommunityLimits.TITLE) String title,
        @NotNull @Size(max = CommunityLimits.BODY) String bodyMarkdown,
        @Size(max = CommunityLimits.SUMMARY) String summary,
        JsonNode context,
        @Valid @Size(max = CommunityLimits.ASSETS) List<AssetInput> assets,
        @Size(max = CommunityLimits.CHANGE_NOTE) String changeNote) {
    }

    public record ContentVersion(@NotNull @Min(0) Long expectedContentVersion) {
    }

    public record PublishRequest(
        @NotNull @Min(0) Long expectedContentVersion,
        @NotNull @Min(0) Long expectedRevisionVersion) {
    }

    public record AcceptRequest(
        @NotNull @Min(0) Long expectedContentVersion,
        @Positive Long answerContentId,
        @Positive Long answerRevisionId) {
    }

    public record LifecycleRequest(
        @NotNull @Min(0) Long expectedContentVersion,
        @NotBlank @Pattern(regexp = "close|reopen|archive|unarchive|soft_delete|open_review") String action,
        @NotBlank @Size(min = 5, max = CommunityLimits.REASON) String reason) {
    }

    public record GrantInput(
        @Size(max = 20) String userId,
        @Positive Long guildId,
        @NotNull @Pattern(regexp = "reader|editor") String permission,
        Instant expiresAt) {
    }

    public record AccessRequest(
        @NotNull @Min(0) Long expectedContentVersion,
        @NotNull @Pattern(regexp = "organization|guild|restricted") String visibility,
        @Positive Long primaryGuildId,
        @NotNull @Valid @Size(max = CommunityLimits.GRANTS) List<GrantInput> grants) {
    }

    public record TagsRequest(
        @NotNull @Min(0) Long expectedContentVersion,
        @NotNull @Size(max = CommunityLimits.TAGS) List<@NotNull @Positive Long> tagIds) {
    }

    public record VoteRequest(@Min(-1) @Max(1) int value) {
    }

    public record CreateComment(
        @NotNull UUID clientRequestId,
        @Positive Long parentCommentId,
        @Positive Long reviewThreadId,
        @NotBlank @Size(max = CommunityLimits.COMMENT) String bodyMarkdown) {
    }

    public record UpdateComment(
        @NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max = CommunityLimits.COMMENT) String bodyMarkdown) {
    }

    public record CreateTag(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 3_000) String description,
        @Size(max = CommunityLimits.ALIASES) List<@NotBlank @Size(max = 100) String> aliases) {
    }

    public record UpdateTag(
        @NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 3_000) String description,
        @NotNull @Pattern(regexp = "active|archived") String status,
        @Size(max = CommunityLimits.ALIASES) List<@NotBlank @Size(max = 100) String> aliases) {
    }

    private ContentRequests() {
    }
}
