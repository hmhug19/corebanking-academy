package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;

public final class ContributionDtos {
    public record BadgeRule(@NotNull @Pattern(regexp = "accepted_answers|received_upvotes|verified_maintenance")
                              String metric,
                              @NotNull @Min(1) @Max(100_000) Integer threshold) {
    }

    public record CreateBadge(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{3,100}") String code,
                               @NotBlank @Size(max = 255) String name, @Size(max = 3_000) String description,
                               @NotNull @Min(1) Integer ruleVersion,
                               @NotNull @Valid BadgeRule rule) {
    }

    public record RetireBadge(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(min = 5,
        max = 3_000) String reason) {
    }

    public record BadgeView(Long badgeId, String code, String name, String description, int ruleVersion, JsonNode rule,
                             String status, Long version, Instant createTime, Instant updateTime) {
    }

    public record BadgeAwardView(Long awardId, BadgeView badge, PersonRef user, String status, Instant awardedAt,
                                  Instant revokedAt, String revokeReason) {
    }

    public record ContributionView(Long contributionEventId, Long orgId, String userId, ContentRef content,
                                    String reasonCode, int pointsDelta, int policyVersion, Long reversesEventId,
                                    Instant occurredAt) {
    }

    public record Contributions(PageResponse<ContributionView> ledger, long eligibleScore) {
    }

    public record ContributorRank(long rank, PersonRef user, long eligibleScore, long acceptedAnswerCount,
                                   long receivedUpvoteCount, long verifiedMaintenanceCount,
                                       List<BadgeAwardView> badges) {
    }

    private ContributionDtos() {
    }
}
