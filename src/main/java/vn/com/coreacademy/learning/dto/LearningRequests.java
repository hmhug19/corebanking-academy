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

import io.swagger.v3.oas.annotations.media.Schema;
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

    /** {@code draft} (tùy chọn) là nội dung nháp ghi cùng lúc với khóa mới; bỏ trống thì tạo bản nháp rỗng. */
    public record CreateCourse(
        @NotNull @Positive Long ownerOrgId,
        @Positive Long ownerTeamId,
        @NotBlank @Size(max = 50) String code,
        @Size(max = 255) String title,
        @Valid DraftContent draft) {
    }

    public record VersionRequest(@NotNull @Min(0) Long expectedVersion) {
    }

    /**
     * Xuất bản kèm đối tượng giao và đơn vị được chia sẻ; hai danh sách bỏ trống hoặc null nghĩa là không có.
     * Mỗi dòng giao chỉ có một đối tượng; các giá trị còn lại của lượt giao lấy từ bản vừa xuất bản.
     */
    public record PublishRequest(
        @NotNull @Min(0) Long expectedVersion,
        @Size(max = LearningLimits.MAX_PUBLISH_ASSIGNMENTS) List<@Valid @NotNull PublishAssignment> assignments,
        @Size(max = LearningLimits.MAX_PUBLISH_SHARES) List<@NotNull @Positive Long> shareOrgIds,
        @Schema(description = "Bắt buộc là true khi bản có nội dung do AI tạo (revision.aiGenerated): người xuất bản xác nhận "
            + "đã rà soát. Bỏ qua với bản không do AI tạo.") Boolean reviewConfirmed) {
        public PublishRequest {
            assignments = assignments == null ? List.of() : assignments;
            shareOrgIds = shareOrgIds == null ? List.of() : shareOrgIds;
        }
    }

    public record PublishAssignment(
        @NotNull Instant startAt,
        Instant dueAt,
        boolean reminderEnabled,
        @Min(0) @Max(365) int reminderDaysBefore,
        @NotNull @Valid TargetInput target) {
    }

    public record CopyRequest(@NotBlank @Size(max = 50) String code) {
    }

    public record CourseState(@NotNull @Min(0) Long expectedVersion,
                              @NotNull @Pattern(regexp = CourseStatus.PATTERN) String status) {
    }

    /**
     * Nội dung bản nháp (mọi trường của {@link DraftRequest} trừ expectedVersion); dùng chung khi lưu nháp và
     * khi tạo khóa.
     * {@code finalQuizId} là bài kiểm tra cuối khóa (loại FINAL_EXAM của chính khóa); bỏ trống nghĩa là không yêu cầu
     * và xóa lựa chọn cũ của bản nháp, như mọi trường khác của cây.
     */
    public record DraftContent(
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
        @Min(0) @Max(100000) int rewardPoints,
        @Positive Long finalQuizId) {
        public DraftContent(Long programId, Long moduleId, String title, String summary, String level,
                            String defaultRequirement, Long coverAssetId, String coverIcon, String coverColor,
                            List<String> objectives, List<ChapterInput> chapters, int rewardPoints) {
            this(programId, moduleId, title, summary, level, defaultRequirement, coverAssetId, coverIcon, coverColor,
                objectives, chapters, rewardPoints, null);
        }

        /** Bản sao với tiêu đề khác; dùng khi tiêu đề của khóa lấy từ trường ngoài nội dung nháp. */
        public DraftContent withTitle(String newTitle) {
            return new DraftContent(programId, moduleId, newTitle, summary, level, defaultRequirement, coverAssetId,
                coverIcon, coverColor, objectives, chapters, rewardPoints, finalQuizId);
        }
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
        @Min(0) @Max(100000) int rewardPoints,
        @Positive Long finalQuizId) {
        public DraftRequest(Long expectedVersion, Long programId, Long moduleId, String title, String summary,
                            String level, String defaultRequirement, Long coverAssetId, String coverIcon,
                            String coverColor, List<String> objectives, List<ChapterInput> chapters, int rewardPoints) {
            this(expectedVersion, programId, moduleId, title, summary, level, defaultRequirement, coverAssetId,
                coverIcon, coverColor, objectives, chapters, rewardPoints, null);
        }

        /** Phần nội dung của payload, bỏ expectedVersion. */
        public DraftContent content() {
            return new DraftContent(programId, moduleId, title, summary, level, defaultRequirement, coverAssetId,
                coverIcon, coverColor, objectives, chapters, rewardPoints, finalQuizId);
        }
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
        @Positive Long quizId,
        @NotNull @Size(max = 100) List<@Valid BlockInput> blocks,
        @Positive Long scormPackageId) {
        public LessonInput(String title, String lessonType, BigDecimal estimatedDurationSeconds,
                           boolean isRequired, Long quizId, List<BlockInput> blocks) {
            this(title, lessonType, estimatedDurationSeconds, isRequired, quizId, blocks, null);
        }
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
        @NotNull @Size(max = 200) List<@Valid StepInput> steps,
        List<VideoChapterInput> videoChapters,
        List<TranscriptLineInput> transcript) {
    }

    /** Mốc của khối video; service kiểm tra số lượng, độ dài, thứ tự và giới hạn thời lượng. */
    public record VideoChapterInput(BigDecimal startSeconds, String title) {
    }

    /** Một đoạn transcript của khối video; cùng luật kiểm tra với mốc. */
    public record TranscriptLineInput(BigDecimal startSeconds, String text) {
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

    /** Lịch mới của đợt giao; thay toàn bộ các trường lịch, dueAt null nghĩa là không có hạn. */
    public record AssignmentScheduleRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull Instant startAt,
        Instant dueAt,
        boolean reminderEnabled,
        @Min(0) @Max(365) int reminderDaysBefore) {
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

    public record LearnerPreferencesRequest(@NotNull Boolean showOnLeaderboard) {
    }
}
