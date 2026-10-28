package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.community.dto.ContentViews.CommentView;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;

public final class ReviewDtos {
    public record Invite(@Size(max = 500) List<@NotBlank @Size(max = 20) String> reviewerUserIds,
                         @Size(max = 50) List<@NotNull @Positive Long> guildIds, Instant expiresAt) {
    }

    public record Respond(@NotNull @Min(0) Long expectedVersion,
                           @NotNull @Pattern(regexp = "accept|decline|complete|cancel") String action) {
    }

    public record CreateThread(@NotNull UUID clientRequestId, @NotNull @Positive Long revisionId,
                               @Positive Long revisionAssetId, @NotNull JsonNode anchor,
                               @NotBlank @Size(max = 10_000) String bodyMarkdown) {
    }

    public record ThreadAction(@NotNull @Min(0) Long expectedVersion,
                                @NotNull @Pattern(regexp = "resolve|reopen") String action,
                                @NotBlank @Size(max = 3_000) String note) {
    }

    public record Conclude(@NotNull @Min(0) Long expectedContentVersion,
                            @NotNull @Positive Long concludedRevisionId,
                            @NotBlank @Size(min = 20, max = 20_000) String conclusion) {
    }

    public record Close(@NotNull @Min(0) Long expectedContentVersion,
                         @NotBlank @Size(min = 5, max = 3_000) String reason) {
    }

    public record ReviewCard(@JsonUnwrapped ContentCard content, String reviewStatus, Instant dueAt,
                             Long currentRevisionId, long openThreadCount, long invitedCount) {
    }

    public record ReviewView(@JsonUnwrapped ContentDetail content, String reviewStatus, Instant dueAt,
                             Long concludedRevisionId, String conclusion, PersonRef concludedBy,
                             Instant concludedAt, long openThreadCount) {
    }

    public record InvitationView(Long invitationId, Long proposalContentId, PersonRef reviewer, PersonRef invitedBy,
                                 String status, Instant respondedAt, Instant expiresAt, Long version,
                                 Instant createTime, Instant updateTime, List<String> capabilities) {
    }

    public record ThreadView(Long threadId, Long proposalContentId, Long revisionId, Long revisionAssetId,
                             JsonNode anchor, String status, PersonRef resolvedBy, Instant resolvedAt,
                             String resolutionNote, CommentView rootComment, Long version,
                             Instant createTime, Instant updateTime, List<String> capabilities) {
    }

    private ReviewDtos() {
    }
}
