package vn.com.coreacademy.assessment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** Read model scalar; không trả entity. Đáp án đúng chỉ xuất hiện ở màn quản trị và phần xem lại sau khi nộp. */
public final class AssessmentViews {
    private AssessmentViews() {
    }

    public record CompetencyView(
        Long id,
        String code,
        String name,
        String description,
        Long parentId,
        Integer sortNo,
        String status,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record QuestionSummary(
        Long id,
        String code,
        Long courseId,
        Long lessonId,
        Long competencyId,
        String competencyName,
        String questionType,
        String contentPreview,
        String difficulty,
        BigDecimal points,
        String status,
        Integer contentVersion,
        Long optionCount,
        Long correctCount,
        Long usedInQuizCount,
        Long rowVersion,
        Instant updateTime,
        Long programId) {
    }

    public record OptionView(Long id, String content, Boolean correct, Integer sortNo) {
    }

    public record QuizRef(Long id, String title, String status) {
    }

    public record QuestionDetail(
        Long id,
        String code,
        Long courseId,
        Long lessonId,
        Long competencyId,
        String questionType,
        String content,
        String explanation,
        String difficulty,
        BigDecimal points,
        String status,
        Integer contentVersion,
        List<OptionView> options,
        List<QuizRef> usedInQuizzes,
        Long rowVersion,
        Instant createTime,
        Instant updateTime,
        Long programId) {
    }

    public record QuizView(
        Long id,
        Long courseId,
        String title,
        String description,
        String quizType,
        String status,
        String selectionMode,
        Integer questionCount,
        Boolean shuffleQuestions,
        Boolean shuffleOptions,
        Integer timeLimitMinutes,
        Instant openAt,
        Instant closeAt,
        Integer graceSeconds,
        BigDecimal passPercent,
        String scoringMode,
        String scoreMethod,
        String reviewPolicy,
        Integer maxAttempts,
        Integer retakeWaitHours,
        Boolean allowRetakeAfterPass,
        Boolean requireLessonsCompleted,
        Instant publishedAt,
        Long rowVersion,
        Instant createTime,
        Instant updateTime) {
    }

    public record QuizSummary(
        Long id,
        Long courseId,
        String title,
        String quizType,
        String status,
        String selectionMode,
        Integer questionCount,
        Integer timeLimitMinutes,
        BigDecimal passPercent,
        Integer maxAttempts,
        Instant openAt,
        Instant closeAt,
        @Schema(description = "Số lượt đã chấm (SUBMITTED/AUTO_SUBMITTED).") Long gradedAttempts,
        Long passedAttempts,
        Long poolSize,
        Long rowVersion,
        Instant updateTime) {
    }

    public record PoolEntry(
        Long questionId,
        String code,
        String contentPreview,
        String questionType,
        String difficulty,
        String status,
        Integer sortNo,
        @Schema(description = "Điểm ghi đè trong đề; NULL = dùng điểm câu hỏi.") BigDecimal pointsOverride,
        BigDecimal effectivePoints) {
    }

    public record QuizDetail(QuizView quiz, List<PoolEntry> pool,
                             @Schema(description = "Số câu ACTIVE trong pool; phải >= questionCount để xuất bản.")
                             Integer activePoolSize) {
    }

    public record AttemptSummary(
        Integer maxAttempts,
        Integer extraAttempts,
        @Schema(description = "Lượt đã dùng, gồm cả lượt đang làm; không tính VOIDED.") Integer attemptsUsed,
        Integer attemptsLeft,
        BigDecimal bestScore,
        BigDecimal lastScore,
        @Schema(description = "Điểm cuối theo scoreMethod của đề (BEST/LAST).") BigDecimal finalScore,
        @Schema(description = "Trạng thái đạt theo scoreMethod: BEST = từng đạt, LAST = lượt chấm gần nhất đạt.")
        Boolean passed,
        Boolean everPassed,
        Long runningAttemptId,
        Instant lastSubmittedAt,
        @Schema(description = "Thời điểm sớm nhất được thi lại khi có retakeWaitHours; NULL nếu không phải chờ.")
        Instant nextAttemptAt) {
    }

    public record LearnerQuiz(
        Long id,
        String title,
        String description,
        String quizType,
        String status,
        Integer questionCount,
        Integer timeLimitMinutes,
        BigDecimal passPercent,
        Integer maxAttempts,
        Integer retakeWaitHours,
        Instant openAt,
        Instant closeAt,
        String scoreMethod,
        String reviewPolicy,
        Boolean shuffleQuestions,
        Boolean hasMultipleChoice) {
    }

    public record CourseRef(Long id, String title) {
    }

    @Schema(description = "Một đề của khóa trong danh sách của học viên; cùng ngữ nghĩa canStart/blockReason với QuizIntro.")
    public record LearnerQuizCard(
        LearnerQuiz quiz,
        AttemptSummary summary,
        @Schema(description = "TRUE khi có thể bắt đầu lượt mới hoặc tiếp tục lượt đang làm.") Boolean canStart,
        @Schema(description = "NOT_OPEN | CLOSED | NOT_READY | LESSONS_REQUIRED | PASSED | NO_ATTEMPTS | WAIT")
        String blockReason,
        @Schema(description = "Thời điểm có thể làm bài khi blockReason là NOT_OPEN hoặc WAIT.") Instant availableAt,
        @Schema(description = "Bài học loại quiz trỏ tới đề trong phiên bản học viên đã nhận; NULL nếu đề chưa được gắn.")
        Long lessonId) {
    }

    public record QuizIntro(
        LearnerQuiz quiz,
        CourseRef course,
        AttemptSummary summary,
        @Schema(description = "TRUE khi có thể bắt đầu lượt mới hoặc tiếp tục lượt đang làm.") Boolean canStart,
        @Schema(description = "NOT_OPEN | CLOSED | NOT_READY | LESSONS_REQUIRED | PASSED | NO_ATTEMPTS | WAIT")
        String blockReason,
        @Schema(description = "Thời điểm có thể làm bài khi blockReason là NOT_OPEN hoặc WAIT.") Instant availableAt,
        Instant serverTime) {
    }

    public record OptionText(Long id, String content) {
    }

    public record SessionQuestion(
        Long attemptQuestionId,
        Integer displayOrder,
        String questionType,
        String content,
        @Schema(description = "Mã câu hỏi trong ngân hàng (questions.code), null khi câu không có mã. Lấy theo "
            + "bản hiện tại của câu, không phải bản chụp lúc phát đề.")
        String code,
        List<OptionText> options,
        List<Long> selectedOptionIds,
        Boolean flagged) {
    }

    public record AttemptSession(
        Long attemptId,
        Long quizId,
        String quizTitle,
        Integer attemptNo,
        String status,
        Instant startedAt,
        Instant deadlineAt,
        @Schema(description = "Giờ server để FE tính đồng hồ đếm ngược, không dùng giờ máy học viên.")
        Instant serverTime,
        @Schema(description = "TRUE khi đã quá hạn nhưng chưa được chấm; FE gọi submit để nhận kết quả.")
        Boolean expired,
        Integer totalQuestions,
        List<SessionQuestion> questions) {
    }

    public record AnswerReceipt(
        Long attemptQuestionId,
        List<Long> selectedOptionIds,
        Boolean flagged,
        Instant savedAt,
        Instant deadlineAt,
        Instant serverTime) {
    }

    public record CompetencyScore(
        Long competencyId,
        String code,
        String name,
        Integer totalQuestions,
        Integer correctCount,
        BigDecimal maxScore,
        BigDecimal score) {
    }

    public record ReviewOption(Long id, String content, Boolean correct, Boolean selected) {
    }

    public record ReviewQuestion(
        Integer displayOrder,
        String questionType,
        String content,
        List<ReviewOption> options,
        Boolean correct,
        BigDecimal points,
        BigDecimal scoreAwarded,
        String explanation) {
    }

    public record AttemptResult(
        Long attemptId,
        Long quizId,
        String quizTitle,
        Long courseId,
        Integer attemptNo,
        String status,
        String submitType,
        Instant startedAt,
        Instant submittedAt,
        Long durationSeconds,
        Integer totalQuestions,
        Integer correctCount,
        BigDecimal rawScore,
        BigDecimal maxScore,
        @Schema(description = "Cắt xuống 2 chữ số thập phân; passed được xét trên giá trị chưa làm tròn.")
        BigDecimal scorePercent,
        BigDecimal passPercent,
        Boolean passed,
        AttemptSummary summary,
        List<CompetencyScore> competencies,
        String reviewPolicy,
        Boolean reviewAvailable,
        @Schema(description = "Đáp án đúng và giải thích; NULL khi reviewPolicy chưa cho phép xem.")
        List<ReviewQuestion> review) {
    }

    public record AttemptRow(
        Long attemptId,
        Integer attemptNo,
        String status,
        String submitType,
        Instant startedAt,
        Instant submittedAt,
        Long durationSeconds,
        Integer totalQuestions,
        Integer correctCount,
        BigDecimal scorePercent,
        Boolean passed) {
    }

    public record AttemptHistory(Long quizId, String quizTitle, AttemptSummary summary, List<AttemptRow> attempts) {
    }

    /** Dòng truy vấn danh sách lượt thi cho quản trị; tên học viên được bổ sung theo lô ở service. */
    public record AttemptListRow(
        Long attemptId,
        String userId,
        Integer attemptNo,
        String status,
        String submitType,
        Instant startedAt,
        Instant submittedAt,
        Integer totalQuestions,
        Integer correctCount,
        BigDecimal scorePercent,
        Boolean passed,
        Long orgId) {
    }

    public record AdminAttemptRow(
        Long attemptId,
        String userId,
        String userName,
        Integer attemptNo,
        String status,
        String submitType,
        Instant startedAt,
        Instant submittedAt,
        Integer totalQuestions,
        Integer correctCount,
        BigDecimal scorePercent,
        Boolean passed,
        Long orgId) {
    }

    public record AdminAttemptQuestion(
        Long attemptQuestionId,
        Integer displayOrder,
        Long questionId,
        Integer questionVersion,
        String questionType,
        String content,
        List<ReviewOption> options,
        @Schema(description = "NULL khi lượt chưa được chấm.") Boolean correct,
        BigDecimal points,
        BigDecimal scoreAwarded,
        String explanation,
        Boolean flagged,
        Instant answeredAt,
        Instant changedAt,
        Integer changeCount) {
    }

    public record AdminAttemptDetail(
        Long attemptId,
        Long quizId,
        String quizTitle,
        String userId,
        String userName,
        Long enrollmentId,
        Integer attemptNo,
        String status,
        String submitType,
        Instant startedAt,
        Instant deadlineAt,
        Instant submittedAt,
        Instant lastSavedAt,
        Long durationSeconds,
        Integer totalQuestions,
        Integer correctCount,
        BigDecimal rawScore,
        BigDecimal maxScore,
        BigDecimal scorePercent,
        BigDecimal passPercent,
        Boolean passed,
        Long orgId,
        Long teamId,
        String clientIp,
        String userAgent,
        String voidReason,
        String voidedBy,
        Instant voidedAt,
        List<CompetencyScore> competencies,
        List<AdminAttemptQuestion> questions,
        @Schema(description = "Lịch sử chấm lại của lượt, cũ nhất trước.") List<RegradeHistory> regrades) {
    }

    public record ExhaustedLearnerCount(String userId, Long attemptsUsed) {
    }

    public record ExhaustedLearner(String userId, String userName, Long attemptsUsed) {
    }

    public record GrantView(
        Long id,
        Long quizId,
        String userId,
        String userName,
        Integer extraAttempts,
        String reason,
        String grantedBy,
        Instant grantedAt,
        Boolean revoked,
        String revokedBy,
        Instant revokedAt) {
    }

    public record BulkResult(@Schema(description = "Số câu hỏi đã được cập nhật.") Integer affected) {
    }

    public record ReportTotals(Long gradedAttempts, Long learners, Long passedLearners, BigDecimal averageScore,
                               Long inProgressAttempts) {
    }

    public record ReportGroupData(Long id, String name, Long gradedAttempts, Long learners, Long passedLearners,
                                  BigDecimal averageScore) {
    }

    public record ReportGroup(
        Long id,
        String name,
        @Schema(description = "Số lượt đã chấm.") Long gradedAttempts,
        @Schema(description = "Số học viên có ít nhất một lượt đã chấm.") Long learners,
        @Schema(description = "Số học viên đạt theo scoreMethod của từng đề.") Long passedLearners,
        @Schema(description = "passedLearners / learners × 100, 2 chữ số; NULL khi chưa có học viên.")
        BigDecimal passRate,
        @Schema(description = "Trung bình điểm cuối (BEST/LAST theo đề) của học viên.") BigDecimal averageScore) {
    }

    public record ReportOverview(
        Long gradedAttempts,
        Long learners,
        Long passedLearners,
        BigDecimal passRate,
        BigDecimal averageScore,
        @Schema(description = "Lượt đang làm trong phạm vi (không lọc theo khoảng thời gian nộp).")
        Long inProgressAttempts,
        List<ReportGroup> byQuiz,
        @Schema(description = "Theo đơn vị lúc thi của lượt chấm gần nhất mỗi học viên.") List<ReportGroup> byOrg) {
    }

    public record ResultRowData(Long attemptId, Long quizId, String quizTitle, Long courseId, String userId,
                                Long orgId, Long teamId, Integer attemptNo, String status, String submitType,
                                Instant startedAt, Instant submittedAt, Integer correctCount, Integer totalQuestions,
                                BigDecimal scorePercent, Boolean passed) {
    }

    public record ReportResultRow(
        Long attemptId,
        Long quizId,
        String quizTitle,
        Long courseId,
        String userId,
        String userName,
        @Schema(description = "Đơn vị tại thời điểm thi.") Long orgId,
        String orgName,
        Long teamId,
        String teamName,
        Integer attemptNo,
        String status,
        String submitType,
        Instant startedAt,
        Instant submittedAt,
        Integer correctCount,
        Integer totalQuestions,
        BigDecimal scorePercent,
        Boolean passed) {
    }

    public record LearnerRowData(String userId, Long enrollmentId, Long orgId, Long teamId, Long attemptsUsed,
                                 BigDecimal bestScore, BigDecimal lastScore, BigDecimal finalScore, Boolean passed,
                                 Instant lastSubmittedAt, String learnerStatus) {
    }

    public record ReportLearnerRow(
        String userId,
        String userName,
        Long enrollmentId,
        @Schema(description = "Đơn vị tại thời điểm ghi danh.") Long orgId,
        String orgName,
        Long teamId,
        String teamName,
        @Schema(description = "NOT_STARTED | IN_PROGRESS | FAILED | PASSED") String learnerStatus,
        Long attemptsUsed,
        @Schema(description = "maxAttempts + lượt cấp thêm − lượt đã dùng, không âm.") Long attemptsLeft,
        BigDecimal bestScore,
        BigDecimal lastScore,
        BigDecimal finalScore,
        Boolean passed,
        Instant lastSubmittedAt) {
    }

    public record CompetencyRowData(String groupKey, Long competencyId, String code, String name,
                                    Long totalQuestions, Long correctCount, BigDecimal maxScore, BigDecimal score) {
    }

    public record ReportCompetencyRow(
        @Schema(description = "Mã cán bộ (groupBy=user) hoặc ID đơn vị lúc thi (groupBy=org).") String groupKey,
        String groupName,
        Long competencyId,
        String code,
        String name,
        Long totalQuestions,
        Long correctCount,
        BigDecimal maxScore,
        BigDecimal score,
        @Schema(description = "score / maxScore × 100, 2 chữ số.") BigDecimal percent) {
    }

    public record QuestionStatRow(
        Long questionId,
        Integer questionVersion,
        @Schema(description = "Mã hiện tại của câu hỏi; NULL nếu câu đã bị xóa cứng khỏi ngân hàng.") String code,
        @Schema(description = "Nội dung theo snapshot lúc phát đề.") String contentPreview,
        Long timesServed,
        Long timesCorrect,
        Long timesBlank,
        @Schema(description = "% trả lời sai, gồm cả bỏ trống.") BigDecimal wrongPercent) {
    }

    public record MyCompetency(
        Long competencyId,
        String code,
        String name,
        Long totalQuestions,
        Long correctCount,
        BigDecimal maxScore,
        BigDecimal score,
        BigDecimal percent) {
    }

    public record RegradeItem(
        Long attemptId,
        String userId,
        String userName,
        Integer attemptNo,
        BigDecimal oldScorePercent,
        BigDecimal newScorePercent,
        Boolean oldPassed,
        Boolean newPassed,
        @Schema(description = "INCREASED (áp dụng khi dryRun=false) | DECREASED (không áp dụng) | UNCHANGED | "
            + "SKIPPED_OPTIONS_CHANGED | SKIPPED_TYPE_CHANGED") String outcome,
        @Schema(description = "TRUE nếu đã ghi vào lượt thi trong lần gọi này.") Boolean applied) {
    }

    public record RegradeResult(
        Boolean dryRun,
        @Schema(description = "Mã đợt; chỉ có khi dryRun=false và có lượt được áp dụng.") String batchId,
        Long questionId,
        @Schema(description = "Đáp án đúng hiện tại của câu (ID phương án) dùng để chấm lại.")
        List<Long> newCorrectOptionIds,
        Integer gradedAttempts,
        Integer increased,
        Integer decreased,
        Integer unchanged,
        Integer skipped,
        Integer applied,
        List<RegradeItem> items) {
    }

    public record RegradeHistory(
        Long id,
        String batchId,
        Long questionId,
        String reason,
        List<Long> oldCorrectOptionIds,
        List<Long> newCorrectOptionIds,
        BigDecimal oldScorePercent,
        BigDecimal newScorePercent,
        Boolean oldPassed,
        Boolean newPassed,
        String regradedBy,
        Instant regradedAt) {
    }

    public record QuestionImportResult(
        @Schema(description = "TRUE = chỉ kiểm tra, chưa ghi dữ liệu.") Boolean dryRun,
        @Schema(description = "Số dòng dữ liệu hợp lệ trong tệp.") Integer rowCount,
        @Schema(description = "Số câu sẽ tạo mới.") Integer toCreate,
        @Schema(description = "Số câu sẽ ghi đè (chỉ khi overwrite=true).") Integer toUpdate,
        @Schema(description = "Số câu đã tạo; 0 khi dryRun.") Integer created,
        @Schema(description = "Số câu đã ghi đè; 0 khi dryRun.") Integer updated) {
    }

    public record PreviewQuestion(
        Integer displayOrder,
        Long questionId,
        String code,
        String questionType,
        String content,
        @Schema(description = "Phương án theo thứ tự sẽ hiển thị, kèm cờ đáp án đúng.") List<OptionView> options,
        BigDecimal points,
        String explanation) {
    }

    public record QuizPreview(
        Long quizId,
        @Schema(description = "Mỗi lần gọi bốc lại theo cấu hình; không tạo lượt thi.") Integer questionCount,
        BigDecimal maxScore,
        List<PreviewQuestion> questions) {
    }

    public record MyAttemptRow(
        Long attemptId,
        Long quizId,
        String quizTitle,
        Long courseId,
        Integer attemptNo,
        String status,
        String submitType,
        Instant startedAt,
        Instant submittedAt,
        Integer totalQuestions,
        Integer correctCount,
        BigDecimal scorePercent,
        Boolean passed) {
    }
}
