package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read model scalar; không trả entity hoặc quan hệ JPA qua API. */
public final class LearningViews {
    private LearningViews() {
    }

    public record TrainingFieldView(
        Long id,
        Long ownerOrgId,
        String code,
        String name,
        String description,
        String status,
        Integer sortNo,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    /** Cây danh mục của một đơn vị: lĩnh vực, chương trình và khóa học, đủ để cập nhật mà không gọi thêm. */
    public record CatalogTreeView(List<CatalogTreeField> fields) {
    }

    public record CatalogTreeField(
        Long id,
        Long ownerOrgId,
        String code,
        String name,
        String description,
        String status,
        Integer sortNo,
        Long rowVersion,
        List<CatalogTreeProgram> programs) {
    }

    public record CatalogTreeProgram(
        Long id,
        Long fieldId,
        Long ownerOrgId,
        String code,
        String name,
        String description,
        String status,
        Integer sortNo,
        Long rowVersion,
        List<CatalogTreeCourse> courses) {
    }

    /** published: khóa đã có bản xuất bản; lifecycleStatus: active, paused hoặc archived. */
    public record CatalogTreeCourse(
        Long id,
        String code,
        String title,
        String lifecycleStatus,
        boolean published) {
    }

    public record TrainingProgramView(
        Long id,
        Long fieldId,
        Long ownerOrgId,
        String code,
        String name,
        String description,
        String status,
        Integer sortNo,
        Long copiedFromProgramId,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record BusinessModuleView(
        Long id,
        Long fieldId,
        String code,
        String name,
        String status,
        Integer sortNo,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record CourseView(
        Long id,
        String code,
        Long ownerOrgId,
        Long ownerTeamId,
        String ownerUserId,
        Long publishedRevisionId,
        String lifecycleStatus,
        Long copiedFromCourseId,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record CourseRevisionView(
        Long id,
        Long courseId,
        Integer revisionNo,
        String state,
        Long basedOnRevisionId,
        Long programId,
        Long moduleId,
        String title,
        String summary,
        String level,
        String defaultRequirement,
        Long coverAssetId,
        String coverIcon,
        String coverColor,
        Instant publishedAt,
        String publishedBy,
        boolean aiGenerated,
        String reviewConfirmedBy,
        Instant reviewConfirmedAt,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record CourseObjectiveView(
        Long id,
        Long revisionId,
        String description,
        Integer sortNo,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record CourseChapterView(
        Long id,
        Long revisionId,
        String title,
        Integer sortNo,
        Long sourceChapterId,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record CourseLessonView(
        Long id,
        Long chapterId,
        String title,
        String lessonType,
        BigDecimal estimatedDurationSeconds,
        Boolean isRequired,
        Integer sortNo,
        Long sourceLessonId,
        Long quizId,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record LessonBlockView(
        Long id,
        Long lessonId,
        String blockType,
        String title,
        String body,
        String bodyFormat,
        String completionMode,
        BigDecimal completionThreshold,
        Boolean isRequired,
        Integer sortNo,
        Long sourceBlockId,
        Long mediaAssetId,
        List<VideoChapterView> videoChapters,
        List<TranscriptLineView> transcript,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record VideoChapterView(BigDecimal startSeconds, String title) {
    }

    public record TranscriptLineView(BigDecimal startSeconds, String text) {
    }

    public record ProcessStepView(
        Long id,
        Long blockId,
        String title,
        String description,
        Integer sortNo,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record LearningAssetView(
        Long id,
        Long ownerOrgId,
        String uploadedBy,
        String storageType,
        String originalName,
        String mimeType,
        Long sizeBytes,
        String checksumSha256,
        BigDecimal durationSeconds,
        Integer pageCount,
        Integer width,
        Integer height,
        String status,
        String mediaType,
        Instant mediaCheckedAt,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record BlockAssetView(
        Long id,
        Long blockId,
        Long assetId,
        String usage,
        String caption,
        String altText,
        Integer sortNo,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record LearningGroupView(
        Long id,
        Long ownerOrgId,
        String code,
        String name,
        String description,
        String status,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record LearningGroupMemberView(
        Long id,
        Long groupId,
        String userId,
        Instant joinedAt,
        Instant leftAt,
        String addedBy,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record CourseShareView(
        Long id,
        Long courseId,
        Long orgId,
        Boolean includeDescendants,
        Boolean canAssign,
        Boolean canSelfEnroll,
        String status,
        String sharedBy,
        Instant sharedAt,
        Instant revokedAt,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record CourseAssignmentView(
        Long id,
        Long courseId,
        Long revisionId,
        Long assigningOrgId,
        String assignedBy,
        String title,
        Instant startAt,
        Instant dueAt,
        String requirement,
        String enrollmentMode,
        Boolean reminderEnabled,
        Integer reminderDaysBefore,
        String status,
        Instant activatedAt,
        String idempotencyKey,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record AssignmentTargetView(
        Long id,
        Long assignmentId,
        String targetType,
        String userId,
        Long teamId,
        Long orgId,
        Long groupId,
        Boolean includeDescendants,
        String targetNameSnapshot,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record AssignmentRecipientView(
        Long id,
        Long assignmentId,
        String userId,
        Long enrollmentId,
        Long orgIdSnapshot,
        Long teamIdSnapshot,
        Instant resolvedAt,
        Instant effectiveStartAt,
        Instant effectiveDueAt,
        String requirement,
        String status,
        Instant revokedAt,
        String revokeReason,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record RecipientTargetSourceView(
        Long id,
        Long recipientId,
        Long targetId,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record CourseEnrollmentView(
        Long id,
        Long courseId,
        Long revisionId,
        String userId,
        Integer cycleNo,
        String enrollmentOrigin,
        Instant selfEnrolledAt,
        Long orgIdSnapshot,
        Long teamIdSnapshot,
        Instant enrolledAt,
        Instant startedAt,
        Instant lastAccessAt,
        Instant completedAt,
        String status,
        BigDecimal progressPercent,
        Long lastLessonId,
        Long lastBlockId,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record LearningReminderView(
        Long id,
        Long recipientId,
        Instant scheduledAt,
        Instant dueAtSnapshot,
        String channel,
        String status,
        Instant sentAt,
        Integer attemptCount,
        String lastErrorCode,
        String idempotencyKey,
        String kind,
        String subject,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record LessonProgressView(
        Long id,
        Long enrollmentId,
        Long lessonId,
        String status,
        BigDecimal progressPercent,
        Instant startedAt,
        Instant lastAccessAt,
        Instant completedAt,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record BlockProgressView(
        Long id,
        Long enrollmentId,
        Long blockId,
        String status,
        BigDecimal progressPercent,
        BigDecimal lastVideoPositionSeconds,
        Integer lastPdfPage,
        Instant startedAt,
        Instant lastAccessAt,
        Instant completedAt,
        Long lastEventSequence,
        Long mediaAssetId,
        BigDecimal watchedSeconds,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record VideoWatchRangeView(
        Long id,
        Long blockProgressId,
        BigDecimal startSecond,
        BigDecimal endSecond,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record CourseCompletionRuleView(
        Long id,
        Long revisionId,
        Boolean requireAllRequiredLessons,
        Boolean requireAllRequiredQuizzes,
        Integer rewardPoints,
        Long rowVersion,
        Instant createTime,
        Instant updateTime,
        Long finalQuizId) {
    }

    public record CourseAssessmentView(
        Long id,
        Long revisionId,
        Long assessmentRevisionId,
        String placement,
        Long lessonId,
        Boolean isRequired,
        BigDecimal passScorePercent,
        Integer maxAttempts,
        Boolean allowRetake,
        Instant opensAt,
        Instant closesAt,
        Integer sortNo,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

}
