package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class LearningDetails {
    private LearningDetails() {
    }

    public record CourseDetail(LearningViews.CourseView course, LearningViews.CourseRevisionView revision,
                               List<LearningViews.CourseObjectiveView> objectives, List<ChapterDetail> chapters,
                               LearningViews.CourseCompletionRuleView completionRule) {
    }

    public record ChapterDetail(LearningViews.CourseChapterView chapter, List<LessonDetail> lessons) {
    }

    public record LessonDetail(LearningViews.CourseLessonView lesson, List<BlockDetail> blocks) {
    }

    public record BlockDetail(LearningViews.LessonBlockView block, List<AssetDetail> assets,
                              List<LearningViews.ProcessStepView> steps) {
    }

    public record AssetDetail(LearningViews.BlockAssetView usage, LearningViews.LearningAssetView asset) {
    }

    public record AssignmentDetail(LearningViews.CourseAssignmentView assignment,
                                     List<LearningViews.AssignmentTargetView> targets,
                                     List<LearningViews.AssignmentRecipientView> recipients,
                                     List<LearningViews.RecipientTargetSourceView> sources) {
    }

    public record GroupDetail(LearningViews.LearningGroupView group,
                              List<LearningViews.LearningGroupMemberView> members) {
    }

    public record EnrollmentDetail(LearningViews.CourseEnrollmentView enrollment, CourseDetail content,
                                     List<LearningViews.LessonProgressView> lessons,
                                     List<LearningViews.BlockProgressView> blocks) {
    }

    public record CourseCard(Long courseId, Long revisionId, String code, String title, Long programId,
                              Long moduleId, String level, String requirement, String publicationState,
                              String lifecycleStatus, long lessonCount, BigDecimal durationSeconds,
                              long assignedCount, Long enrollmentId, String learningStatus,
                              BigDecimal progressPercent, Instant dueAt, boolean assigned, boolean overdue,
                              Instant updateTime, Integer cycleNo, Instant startedAt, Instant lastAccessAt,
                              Instant completedAt, Long lastLessonId, Long lastBlockId,
                              Long completedRequiredLessonCount, Long requiredLessonCount, String deadlineStatus,
                              Long mediaAssetId, Integer lastPdfPage, BigDecimal lastVideoPositionSeconds) {
    }

    public record ProgressResult(LearningViews.BlockProgressView block,
                                  LearningViews.LessonProgressView lesson,
                                  LearningViews.CourseEnrollmentView enrollment, MediaViews.Receipt session) {
    }
}
