package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Payload chỉ chứa trường được phép sửa; actor và tổng tiến độ luôn do backend xác định. */
public final class LearningRequests {
    private LearningRequests() {
    }

    public record CatalogRequest(
        @NotNull @Positive Long ownerOrgId,
        @Positive Long fieldId,
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 10000) String description,
        @NotNull @Pattern(regexp = "draft|published|paused|archived") String status,
        @Min(0) int sortNo) {
    }

    public record CreateCourse(
        @NotNull @Positive Long ownerOrgId,
        @Positive Long ownerTeamId,
        @NotBlank @Size(max = 50) String code,
        @Size(max = 255) String title) {
    }

    public record VersionRequest(@NotNull @Min(0) Long expectedVersion) {
    }

    public record CopyRequest(@NotBlank @Size(max = 50) String code) {
    }

    public record CourseState(@NotNull @Min(0) Long expectedVersion,
                              @NotNull @Pattern(regexp = "active|paused|archived") String status) {
    }

    public record DraftRequest(
        @NotNull @Min(0) Long expectedVersion,
        @Positive Long programId,
        @Positive Long moduleId,
        @Size(max = 255) String title,
        @Size(max = 30000) String summary,
        @Pattern(regexp = "basic|intermediate|advanced") String level,
        @NotNull @Pattern(regexp = "mandatory|optional") String defaultRequirement,
        @Positive Long coverAssetId,
        @Size(max = 50) String coverIcon,
        @Pattern(regexp = "#[0-9A-Fa-f]{6}") String coverColor,
        @NotNull @Size(max = 100) List<@NotBlank @Size(max = 2000) String> objectives,
        @NotNull @Size(max = 100) List<@Valid ChapterInput> chapters,
        @Min(0) @Max(100000) int rewardPoints) {
    }

    public record ChapterInput(@NotBlank @Size(max = 255) String title,
                               @NotNull @Size(max = 100) List<@Valid LessonInput> lessons) {
    }

    public record LessonInput(
        @NotBlank @Size(max = 255) String title,
        @NotNull @Pattern(regexp = "content|quiz") String lessonType,
        @NotNull @DecimalMin("0") @DecimalMax("86400") BigDecimal estimatedDurationSeconds,
        boolean isRequired,
        @NotNull @Size(max = 100) List<@Valid BlockInput> blocks) {
    }

    public record BlockInput(
        @NotNull @Pattern(regexp = "video|pdf|text|process|image|attachment") String blockType,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 100000) String body,
        @Pattern(regexp = "markdown|html|plain") String bodyFormat,
        @NotNull @Pattern(regexp = "manual|video_coverage|pdf_end|open") String completionMode,
        @DecimalMin("1") @DecimalMax("100") BigDecimal completionThreshold,
        boolean isRequired,
        @NotNull @Size(max = 20) List<@Valid AssetUse> assets,
        @NotNull @Size(max = 200) List<@Valid StepInput> steps) {
    }

    public record AssetUse(
        @NotNull @Positive Long assetId,
        @NotNull @Pattern(regexp = "primary|attachment|image|poster") String usage,
        @Size(max = 5000) String caption,
        @Size(max = 255) String altText) {
    }

    public record StepInput(@NotBlank @Size(max = 255) String title,
                            @Size(max = 10000) String description) {
    }

    public record GroupRequest(
        @NotNull @Positive Long ownerOrgId,
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 10000) String description) {
    }

    public record MembersRequest(@NotNull @Min(0) Long expectedVersion,
                                  @NotNull @Size(max = 500) List<@NotBlank @Size(max = 20) String> userIds) {
    }

    public record ShareRequest(@NotNull @Positive Long orgId, boolean includeDescendants,
                               boolean canAssign, boolean canSelfEnroll, boolean active) {
    }

    public record AssignmentRequest(
        @NotNull @Positive Long revisionId,
        @NotNull @Positive Long assigningOrgId,
        @Size(max = 255) String title,
        @NotNull Instant startAt,
        Instant dueAt,
        @NotNull @Pattern(regexp = "mandatory|optional") String requirement,
        @NotNull @Pattern(regexp = "continue|retake") String enrollmentMode,
        boolean reminderEnabled,
        @Min(0) @Max(365) int reminderDaysBefore,
        @NotBlank @Size(max = 100) String idempotencyKey,
        @NotEmpty @Size(max = 100) List<@Valid TargetInput> targets) {
    }

    public record TargetInput(
        @NotNull @Pattern(regexp = "user|team|org|learning_group") String targetType,
        @Size(max = 20) String userId,
        @Positive Long teamId,
        @Positive Long orgId,
        @Positive Long groupId,
        boolean includeDescendants) {
    }

    public record RevokeRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    public record ProgressRequest(
        @NotNull @Min(0) Long expectedVersion,
        @Min(1) long sequence,
        boolean complete,
        @DecimalMin("0") BigDecimal videoPositionSeconds,
        @DecimalMin("0") BigDecimal watchedFromSeconds,
        @DecimalMin("0") BigDecimal watchedToSeconds,
        @Min(1) Integer pdfPage) {
    }
}
