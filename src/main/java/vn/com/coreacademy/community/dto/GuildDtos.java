package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import vn.com.coreacademy.community.dto.ContentViews.PersonRef;

public final class GuildDtos {
    public record CreateGuild(
        @NotNull UUID clientRequestId,
        @NotBlank @Size(min = 3, max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @NotBlank @Size(min = 3, max = 255) String name,
        @Size(max = 10_000) String description,
        @NotNull @Pattern(regexp = "organization|private") String visibility,
        @NotNull @Pattern(regexp = "open|approval|invite") String joinPolicy) {
    }

    public record UpdateGuild(
        @NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(min = 3, max = 255) String name,
        @Size(max = 10_000) String description,
        @NotNull @Pattern(regexp = "organization|private") String visibility,
        @NotNull @Pattern(regexp = "open|approval|invite") String joinPolicy,
        @NotNull @Pattern(regexp = "active|archived") String status) {
    }

    public record InviteMembers(
        @NotNull @Size(min = 1, max = 100) List<@NotBlank @Size(max = 20) String> userIds,
        Instant expiresAt) {
    }

    public record MemberAction(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull @Pattern(regexp = "approve|reject|remove|change_role") String action,
        @Pattern(regexp = "member|moderator") String memberRole) {
    }

    public record InvitationResponse(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull @Pattern(regexp = "accept|decline") String action) {
    }

    public record VersionRequest(@NotNull @Min(0) Long expectedVersion) {
    }

    public record TransferOwner(
        @NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max = 20) String successorUserId) {
    }

    public record GuildView(Long guildId, String slug, String name, String description, String ownerUserId,
                            String visibility, String joinPolicy, String status, Long version,
                            MembershipView membership, boolean ownerUnavailable, boolean canManage, PersonRef owner,
                            boolean effectiveReadOnly, MembershipView myMembership, long visibleContentCount,
                            long visibleMemberCount, Map<String, Boolean> capabilities, Instant createTime,
                                Instant updateTime) {
    }

    public record MembershipView(Long membershipId, Long guildId, String userId, String memberRole, String status,
                                 Instant joinedAt, Instant invitationExpiresAt, Long version, PersonRef user,
                                 Instant leftAt, Instant invitedAt, Map<String, Boolean> capabilities,
                                 Instant createTime, Instant updateTime) {
    }

    private GuildDtos() {
    }
}
