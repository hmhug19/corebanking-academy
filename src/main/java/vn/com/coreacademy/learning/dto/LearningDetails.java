package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.RatingSummary;

public final class LearningDetails {
    private LearningDetails() {
    }

    public record CourseDetail(LearningViews.CourseView course, LearningViews.CourseRevisionView revision,
                               List<LearningViews.CourseObjectiveView> objectives, List<ChapterDetail> chapters,
                               LearningViews.CourseCompletionRuleView completionRule, Long finalQuizId) {
        public CourseDetail(LearningViews.CourseView course, LearningViews.CourseRevisionView revision,
                            List<LearningViews.CourseObjectiveView> objectives, List<ChapterDetail> chapters,
                            LearningViews.CourseCompletionRuleView completionRule) {
            this(course, revision, objectives, chapters, completionRule,
                completionRule == null ? null : completionRule.finalQuizId());
        }
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
                              Long mediaAssetId, Integer lastPdfPage, BigDecimal lastVideoPositionSeconds, Long fieldId,
                              String fieldName, String programName, String moduleName, boolean failed,
                              BigDecimal bestScorePercent, RatingSummary rating, Long coverAssetId) {
        /**
         * Dạng không có rating cho truy vấn search: Hibernate dựng thẻ từ các cột của câu SQL, còn điểm đánh giá của
         * khóa do service điền theo từng trang bằng withRating. coverAssetId là ảnh bìa của đúng revision trên thẻ;
         * ai thấy thẻ thì đọc được ảnh đó qua /learning-assets/{id}/content.
         */
        public CourseCard(Long courseId, Long revisionId, String code, String title, Long programId,
                          Long moduleId, String level, String requirement, String publicationState,
                          String lifecycleStatus, long lessonCount, BigDecimal durationSeconds,
                          long assignedCount, Long enrollmentId, String learningStatus,
                          BigDecimal progressPercent, Instant dueAt, boolean assigned, boolean overdue,
                          Instant updateTime, Integer cycleNo, Instant startedAt, Instant lastAccessAt,
                          Instant completedAt, Long lastLessonId, Long lastBlockId,
                          Long completedRequiredLessonCount, Long requiredLessonCount, String deadlineStatus,
                          Long mediaAssetId, Integer lastPdfPage, BigDecimal lastVideoPositionSeconds, Long fieldId,
                          String fieldName, String programName, String moduleName, boolean failed,
                          BigDecimal bestScorePercent, Long coverAssetId) {
            this(courseId, revisionId, code, title, programId, moduleId, level, requirement, publicationState,
                lifecycleStatus, lessonCount, durationSeconds, assignedCount, enrollmentId, learningStatus,
                progressPercent, dueAt, assigned, overdue, updateTime, cycleNo, startedAt, lastAccessAt,
                completedAt, lastLessonId, lastBlockId, completedRequiredLessonCount, requiredLessonCount,
                deadlineStatus, mediaAssetId, lastPdfPage, lastVideoPositionSeconds, fieldId, fieldName,
                programName, moduleName, failed, bestScorePercent, null, coverAssetId);
        }

        /**
         * Chứng nhận của chu kỳ trên thẻ này: id ghi danh khi chu kỳ đã hoàn thành, ngược lại null. Giá trị suy ra, nên
         * quy tắc "chu kỳ hoàn thành là một chứng nhận" vẫn nằm ở server và SQL tìm kiếm không đổi.
         */
        @JsonProperty("certificateId")
        public Long certificateId() {
            return ProgressStatus.COMPLETED.equals(learningStatus) ? enrollmentId : null;
        }

        /**
         * Điểm cao nhất của lượt đã chấm trên bài thi cuối khóa (PUBLISHED, chưa xóa); null khi khóa không có bài thi
         * cuối, chưa có lượt chấm, hoặc ở chế độ MANAGE. Search chỉ để null, service điền theo từng trang.
         */
        public CourseCard withBestScorePercent(BigDecimal score) {
            return new CourseCard(courseId, revisionId, code, title, programId, moduleId, level, requirement,
                publicationState, lifecycleStatus, lessonCount, durationSeconds, assignedCount, enrollmentId,
                learningStatus, progressPercent, dueAt, assigned, overdue, updateTime, cycleNo, startedAt,
                lastAccessAt, completedAt, lastLessonId, lastBlockId, completedRequiredLessonCount,
                requiredLessonCount, deadlineStatus, mediaAssetId, lastPdfPage, lastVideoPositionSeconds, fieldId,
                fieldName, programName, moduleName, failed, score, rating, coverAssetId);
        }

        /**
         * Điểm đánh giá của khóa: trung bình các đánh giá bài học sống qua mọi phiên bản, làm tròn một chữ số, và số
         * đánh giá; null khi khóa chưa có đánh giá nào. Search chỉ để null, service điền theo từng trang.
         */
        public CourseCard withRating(RatingSummary courseRating) {
            return new CourseCard(courseId, revisionId, code, title, programId, moduleId, level, requirement,
                publicationState, lifecycleStatus, lessonCount, durationSeconds, assignedCount, enrollmentId,
                learningStatus, progressPercent, dueAt, assigned, overdue, updateTime, cycleNo, startedAt,
                lastAccessAt, completedAt, lastLessonId, lastBlockId, completedRequiredLessonCount,
                requiredLessonCount, deadlineStatus, mediaAssetId, lastPdfPage, lastVideoPositionSeconds, fieldId,
                fieldName, programName, moduleName, failed, bestScorePercent, courseRating, coverAssetId);
        }
    }

    /** Một lĩnh vực hoặc giá trị lọc không thuộc lĩnh vực nào. */
    public record Facet(Long id, String code, String name) {
    }

    /** Chương trình hoặc phân hệ; fieldId là lĩnh vực chứa nó. */
    public record FieldFacet(Long id, Long fieldId, String code, String name) {
    }

    public record LearnerFacets(List<Facet> fields, List<FieldFacet> programs, List<FieldFacet> modules) {
    }

    /**
     * Khóa học viên thấy kèm nội dung: bản đã nhận khi có lần học, bản xuất bản hiện hành khi chưa đăng ký (khi đó mỗi
     * bài có danh sách blocks rỗng).
     */
    public record LearnerCourse(CourseCard card, CourseDetail content) {
    }

    public record ProgressResult(LearningViews.BlockProgressView block,
                                  LearningViews.LessonProgressView lesson,
                                  LearningViews.CourseEnrollmentView enrollment, MediaViews.Receipt session) {
    }
}
