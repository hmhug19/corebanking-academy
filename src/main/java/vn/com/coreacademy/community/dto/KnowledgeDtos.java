package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.PreferenceDtos.GuildRef;

public final class KnowledgeDtos {
    public record HelpRequest(@Size(min = 1, max = 20) String targetUserId, @Positive Long targetGuildId,
        Instant dueAt) {
    }

    public record HelpAction(@NotNull @Min(0) Long expectedVersion,
                             @NotNull @Pattern(regexp = "accept|decline|resolve|cancel") String action) {
    }

    public record HelpRequestView(Long helpRequestId, Long questionId, PersonRef requestedBy, PersonRef targetUser,
                                   GuildRef targetGuild, String status, Instant dueAt, PersonRef respondedBy,
                                   Instant respondedAt, Long version, Instant createTime, Instant updateTime,
                                   Map<String, Boolean> capabilities) {
    }

    public record KnowledgeAction(@NotNull @Min(0) Long expectedContentVersion,
        @NotNull @Pattern(regexp = "claim|release|fulfill|close|reopen") String action,
        @Size(max = 20) List<@NotNull @Positive Long> fulfillmentContentIds,
        @Size(min = 5, max = 3_000) String reason) {
    }

    public record KnowledgeRequestView(@JsonUnwrapped ContentDetail content, String requestStatus, PersonRef claimedBy,
                                       Instant claimedAt, Instant dueAt, Instant fulfilledAt,
                                       List<ContentRef> fulfillments) {
    }

    public record KnowledgeRequestCard(@JsonUnwrapped ContentCard content, String requestStatus, PersonRef claimedBy,
                                       Instant dueAt, Instant fulfilledAt) {
    }

    private KnowledgeDtos() {
    }
}
