package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

import vn.com.coreacademy.learning.constants.LearningCodes.AssetUsage;
import vn.com.coreacademy.learning.constants.LearningCodes.BlockType;
import vn.com.coreacademy.learning.constants.LearningCodes.BodyFormat;
import vn.com.coreacademy.learning.constants.LearningCodes.CatalogStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.CompletionMode;
import vn.com.coreacademy.learning.constants.LearningCodes.CourseStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.EnrollmentMode;
import vn.com.coreacademy.learning.constants.LearningCodes.LessonType;
import vn.com.coreacademy.learning.constants.LearningCodes.Level;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressEvent;
import vn.com.coreacademy.learning.constants.LearningCodes.Requirement;
import vn.com.coreacademy.learning.constants.LearningCodes.TargetType;
import vn.com.coreacademy.learning.constants.LearningLimits;

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
        @NotNull @Pattern(regexp = CatalogStatus.PATTERN) String status,
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
                              @NotNull @Pattern(regexp = CourseStatus.PATTERN) String status) {
    }

    public record DraftRequest(
        @NotNull @Min(0) Long expectedVersion,
        @Positive Long programId,
        @Positive Long moduleId,
        @Size(max = 255) String title,
        @Size(max = 30000) String summary,
        @Pattern(regexp = Level.PATTERN) String level,
        @NotNull @Pattern(regexp = Requirement.PATTERN) String defaultRequirement,
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
        @NotNull @Pattern(regexp = LessonType.PATTERN) String lessonType,
        @NotNull @DecimalMin("0") @DecimalMax(LearningLimits.MAX_MEDIA_DURATION_TEXT)
        BigDecimal estimatedDurationSeconds,
        boolean isRequired,
        @NotNull @Size(max = 100) List<@Valid BlockInput> blocks) {
    }

    public record BlockInput(
        @NotNull @Pattern(regexp = BlockType.PATTERN) String blockType,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 100000) String body,
        @Pattern(regexp = BodyFormat.PATTERN) String bodyFormat,
        @NotNull @Pattern(regexp = CompletionMode.PATTERN) String completionMode,
        @DecimalMin("1") @DecimalMax("100") BigDecimal completionThreshold,
        boolean isRequired,
        @NotNull @Size(max = 20) List<@Valid AssetUse> assets,
        @NotNull @Size(max = 200) List<@Valid StepInput> steps) {
    }

    public record AssetUse(
        @NotNull @Positive Long assetId,
        @NotNull @Pattern(regexp = AssetUsage.PATTERN) String usage,
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
                                  @NotNull @Size(max =
                                      LearningLimits.MAX_GROUP_MEMBERS) List<@NotBlank @Size(max =
                                          20) String> userIds) {
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
        @NotNull @Pattern(regexp = Requirement.PATTERN) String requirement,
        @NotNull @Pattern(regexp = EnrollmentMode.PATTERN) String enrollmentMode,
        boolean reminderEnabled,
        @Min(0) @Max(365) int reminderDaysBefore,
        @NotBlank @Size(max = 100) String idempotencyKey,
        @NotEmpty @Size(max = LearningLimits.MAX_ASSIGNMENT_TARGETS) List<@Valid TargetInput> targets) {
    }

    public record TargetInput(
        @NotNull @Pattern(regexp = TargetType.PATTERN) String targetType,
        @Size(max = 20) String userId,
        @Positive Long teamId,
        @Positive Long orgId,
        @Positive Long groupId,
        boolean includeDescendants) {
    }

    public record RevokeRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    public record ProgressRequest(
        @NotNull UUID sessionId,
        @NotNull @Min(1) Long sessionFence,
        @NotNull @Pattern(regexp = ProgressEvent.PATTERN)
        String eventType,
        @Positive Long assetId,
        boolean playing,
        @NotNull @Min(0) Long expectedVersion,
        @Min(1) long sequence,
        boolean complete,
        @DecimalMin("0") BigDecimal videoPositionSeconds,
        @DecimalMin("0") BigDecimal watchedFromSeconds,
        @DecimalMin("0") BigDecimal watchedToSeconds,
        @Min(1) Integer pdfPage) {
    }
}
