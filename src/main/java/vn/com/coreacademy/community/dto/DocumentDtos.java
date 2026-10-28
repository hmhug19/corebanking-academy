package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;

public final class DocumentDtos {
    public record WikiLocation(@NotNull @Min(0) Long expectedContentVersion, @Positive Long parentPageId,
                                @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
                                @Min(0) int sortNo) {
    }

    public record ReviewSchedule(@NotNull @Min(0) Long expectedContentVersion, LocalDate reviewDueAt) {
    }

    public record RevisionDecision(@NotNull @Min(0) Long expectedContentVersion,
                                    @NotNull @Min(0) Long expectedRevisionVersion,
                                    @NotNull @Pattern(regexp = "approved|changes_requested|rejected") String decision,
                                    @Size(max = 3_000) String note) {
    }

    public record MaintenanceRequest(@NotNull @Min(0) Long expectedContentVersion,
                                     @NotNull @Positive Long revisionId,
                                     @NotNull @Pattern(regexp = "current|needs_update|obsolete") String outcome,
                                     @Size(max = 3_000) String note, LocalDate nextReviewDueAt) {
    }

    public record WikiPageView(@JsonUnwrapped ContentDetail content, Long guildId, Long parentPageId,
                               String slug, Integer sortNo, LocalDate reviewDueAt, boolean isOutdated) {
    }

    public record WikiPageCard(@JsonUnwrapped ContentCard content, Long guildId, Long parentPageId, String slug,
                               Integer sortNo, String documentClass, LocalDate reviewDueAt, boolean isOutdated) {
    }

    public record RadarCard(@JsonUnwrapped ContentCard content, Long guildId, String technologyName,
                            String technologyVersion, String stage, String approvalScope, LocalDate effectiveFrom,
                            LocalDate reviewDueAt) {
    }

    public record ApprovalView(Long approvalId, Long revisionId, PersonRef reviewer, String decision, String note,
                               Instant decidedAt, Long contentVersion, Long revisionVersion, Long version,
                               Instant createTime, Instant updateTime) {
    }

    public record ApprovalResult(ApprovalView approval, @JsonUnwrapped ContentDetail content) {
    }

    public record MaintenanceReviewView(Long maintenanceReviewId, Long contentId, Long revisionId, PersonRef reviewer,
                                        String outcome, String note, Instant reviewedAt, LocalDate nextReviewDueAt,
                                        Long contentVersion, Long version, Instant createTime, Instant updateTime) {
    }

    public record RevisionCard(Long revisionId, Long contentId, Integer revisionNo, Long revisionVersion,
                                String title, String revisionStatus, Instant publishedAt, String changeNote,
                                boolean canApprove, Map<String, Boolean> capabilities) {
    }

    private DocumentDtos() {
    }
}
