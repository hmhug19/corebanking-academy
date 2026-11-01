package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Projection gọn cho trang chủ/báo cáo, không tải entity hoặc cây nội dung. */
public final class LearningAnalytics {
    private LearningAnalytics() {
    }

    public record Home(Counts counts, NextAction nextAction, List<Enrollment> deadlines) {
    }

    public record Counts(Long enrollments, Long learners, Long courses, Long assigned, Long mandatory,
                         Long completed, Long inProgress, Long notStarted, Long overdue,
                         BigDecimal averageProgress, BigDecimal completionRate) {
    }

    public record Summary(Instant dataAsOf, String timeZone, Counts counts) {
    }

    public record Enrollment(Long id, String userId, Long courseId, String courseCode, String courseTitle,
                             Long revisionId, Long programId, String programName, Long orgId, Long teamId,
                             String status, BigDecimal progressPercent, Boolean assigned, Boolean mandatory,
                             Instant enrolledAt, Instant startedAt, Instant lastAccessAt, Instant completedAt,
                             Instant dueAt, Boolean overdue, Boolean canStudy) {
    }

    public record EnrollmentRow(Enrollment enrollment, String fullName, String orgName, String teamName) {
    }

    public record Group(String id, String name, Long enrollments, Long learners, Long courses,
                        Long completed, Long inProgress, Long notStarted, Long overdue,
                        BigDecimal averageProgress, BigDecimal completionRate) {
    }

    public record NextAction(Long enrollmentId, Long courseId, String courseTitle, Long lessonId,
                             Long blockId, String blockType, Long quizId, Long assetId,
                             Integer lastPdfPage, BigDecimal lastVideoPositionSeconds) {
    }
}
