package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.ContentViews.TagView;

public final class PreferenceDtos {
    public record SavePreferences(
        @NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max = 100) String timeZone,
        @NotNull @Pattern(regexp = "off|daily|weekly") String digestFrequency,
        @NotNull LocalTime digestLocalTime,
        @Min(1) @Max(7) Integer digestWeekday,
        @NotNull JsonNode notificationPreferences) {
    }

    public record PreferencesView(String userId, Instant onboardingCompletedAt, String timeZone,
                                  String digestFrequency, LocalTime digestLocalTime, Integer digestWeekday,
                                  Instant nextDigestAt, JsonNode notificationPreferences, Long version,
                                  Instant createTime, Instant updateTime) {
    }

    public record Onboarding(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull @Size(max = 30) List<@NotNull @Positive Long> tagIds,
        @NotNull @Size(max = 30) List<@NotNull @Positive Long> guildIdsToFollow,
        boolean complete) {
    }

    public record SubscriptionRequest(
        @Positive Long contentId, @Positive Long tagId, @Positive Long guildId,
        @Size(min = 1, max = 20) String authorUserId,
        @NotNull @Pattern(regexp = "instant|digest|muted|off") String mode) {
    }

    public record SubscriptionView(Long subscriptionId, String userId, String targetType, String targetId,
                                   String targetLabel, String deliveryMode, Long version,
                                   Instant createTime, Instant updateTime) {
    }

    public record ExpertiseItem(@NotNull @Positive Long tagId, @Size(max = 3_000) String description,
                                boolean availableForHelp) {
    }

    public record SaveExpertise(@NotNull @Min(0) Long expectedPreferenceVersion,
                                @NotNull @Valid @Size(max = 50) List<ExpertiseItem> items) {
    }

    public record ExpertiseView(Long expertiseId, PersonRef user, TagView tag, String description,
                                boolean availableForHelp) {
    }

    public record ExpertiseResult(List<ExpertiseView> items, Long preferenceVersion) {
    }

    public record GuildRef(Long guildId, String name) {
    }

    public record ExpertView(PersonRef user, List<ExpertiseView> expertise, List<GuildRef> visibleGuilds,
                             boolean availableForHelp) {
    }

    private PreferenceDtos() {
    }
}
