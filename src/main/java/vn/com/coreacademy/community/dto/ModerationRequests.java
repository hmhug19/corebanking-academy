package vn.com.coreacademy.community.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import vn.com.coreacademy.community.dto.ContentViews.PersonRef;

public final class ModerationRequests {
    public record ModeratorRequest(
        @NotNull @Positive Long orgId,
        boolean active,
        @NotBlank @Size(min = 5, max = 3_000) String reason,
        @NotNull @Min(0) Long expectedVersion) {
    }

    public record ModeratorView(Long moderatorId, String userId, Long orgId, String status, Long version,
                                 PersonRef user, PersonRef grantedBy, Instant grantedAt, Instant revokedAt,
                                 Instant createTime, Instant updateTime) {
    }

    public record CreateReport(@Positive Long contentId, @Positive Long commentId,
                                @NotBlank @Size(max = 100) String reasonCode,
                                @Size(max = 3_000) String description) {
    }

    public record AssignReport(@NotNull @Min(0) Long expectedVersion,
                                @NotBlank @Size(max = 20) String moderatorUserId) {
    }

    public record ModerationActionRequest(
        @NotNull @Min(0) Long expectedReportVersion,
        @NotNull @Min(0) Long expectedTargetVersion,
        @NotNull @Pattern(regexp = "hide|restore|restore_deleted|soft_delete|close|assign_maintainer|warn|dismiss")
        String action,
        @NotBlank @Size(min = 5, max = 3_000) String reason,
        @Size(max = 20) String maintainerUserId,
        @Positive Long restoreActionId) {
    }

    private ModerationRequests() {
    }
}
