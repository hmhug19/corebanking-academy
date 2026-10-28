package vn.com.coreacademy.community.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class CommunityManagementDtos {
    public record MergeTag(@NotNull @Min(0) Long expectedVersion, @NotNull @Positive Long targetTagId,
                            @NotBlank @Size(min = 5, max = 3_000) String reason) {
    }

    public record Maintainer(@NotNull @Min(0) Long expectedContentVersion,
                             @NotBlank @Size(max = 20) String maintainerUserId) {
    }

    public record DuplicateQuestion(@NotNull @Min(0) Long expectedContentVersion, @Positive Long targetQuestionId,
                                    @NotBlank @Size(min = 5, max = 3_000) String reason, @Positive Long reportId) {
    }

    public record PinRequest(boolean pinned) {
    }

    public record PinView(Long guildId, Long contentId, boolean pinned) {
    }

    private CommunityManagementDtos() {
    }
}
