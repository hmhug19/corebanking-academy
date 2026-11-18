package vn.com.coreacademy.assessment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.Difficulty;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuestionType;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizType;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ReviewPolicy;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoreMethod;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoringMode;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.SelectionMode;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.common.exception.UnsupportedFieldException;

/** Payload chỉ chứa trường được phép sửa; actor, điểm và kết quả chấm luôn do backend xác định. */
public final class AssessmentRequests {
    private AssessmentRequests() {
    }

    public record CompetencyRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 4000) String description,
        @Positive Long parentId,
        @Min(0) int sortNo,
        @NotNull @Pattern(regexp = ItemStatus.PATTERN) String status) {
    }

    public record VersionRequest(@NotNull @Min(0) Long expectedVersion) {
    }

    public record OptionInput(
        @Schema(description = "ID phương án hiện có để sửa tại chỗ; bỏ trống khi thêm phương án mới.")
        @Positive Long id,
        @NotBlank @Size(max = AssessmentLimits.MAX_QUESTION_TEXT) String content,
        boolean correct) {
    }

    public record QuestionRequest(
        @Schema(description = "Nguồn khóa học của client cũ; tạo mới theo chương trình chỉ gửi programId.")
        @Positive Long courseId,
        @Positive Long lessonId,
        @NotNull @Positive Long competencyId,
        @Schema(description = "Mã câu hỏi; bỏ trống khi tạo để backend sinh Q<ID>.")
        @Size(max = 50) String code,
        @NotNull @Pattern(regexp = QuestionType.PATTERN) String questionType,
        @NotBlank @Size(max = AssessmentLimits.MAX_QUESTION_TEXT) String content,
        @Size(max = AssessmentLimits.MAX_QUESTION_TEXT) String explanation,
        @NotNull @Pattern(regexp = Difficulty.PATTERN) String difficulty,
        @NotNull @DecimalMin("0.01") @DecimalMax("9999.99") @Digits(integer = 4, fraction = 2) BigDecimal points,
        @NotNull @Pattern(regexp = ItemStatus.PATTERN) String status,
        @Schema(description = "Danh sách đầy đủ phương án; phương án cũ không có trong danh sách sẽ bị xóa mềm.")
        @NotNull @Size(min = AssessmentLimits.MIN_OPTIONS, max = AssessmentLimits.MAX_OPTIONS)
        List<@Valid @NotNull OptionInput> options,
        @Schema(description = "Chương trình sở hữu ngân hàng câu hỏi; không được đổi khi cập nhật.")
        @Positive Long programId) {
        public QuestionRequest(Long courseId, Long lessonId, Long competencyId, String code, String questionType,
                               String content, String explanation, String difficulty, BigDecimal points,
                               String status, List<OptionInput> options) {
            this(courseId, lessonId, competencyId, code, questionType, content, explanation, difficulty, points,
                status, options, null);
        }
    }

    public record QuizRequest(
        @Schema(description = "Bắt buộc khi tạo. Khi cập nhật bỏ trống hoặc gửi đúng khóa hiện tại.")
        @Positive Long courseId,
        @NotBlank @Size(max = AssessmentLimits.MAX_QUIZ_TITLE) String title,
        @Size(max = 10000) String description,
        @NotNull @Pattern(regexp = QuizType.PATTERN) String quizType,
        @NotNull @Pattern(regexp = SelectionMode.PATTERN) String selectionMode,
        @NotNull @Min(1) @Max(AssessmentLimits.MAX_QUESTION_COUNT) Integer questionCount,
        @NotNull Boolean shuffleQuestions,
        @NotNull Boolean shuffleOptions,
        @NotNull @Min(1) @Max(AssessmentLimits.MAX_TIME_LIMIT_MINUTES) Integer timeLimitMinutes,
        Instant openAt,
        Instant closeAt,
        @NotNull @Min(0) @Max(AssessmentLimits.MAX_GRACE_SECONDS) Integer graceSeconds,
        @NotNull @DecimalMin("0.01") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal passPercent,
        @NotNull @Pattern(regexp = ScoringMode.PATTERN) String scoringMode,
        @NotNull @Pattern(regexp = ScoreMethod.PATTERN) String scoreMethod,
        @NotNull @Pattern(regexp = ReviewPolicy.PATTERN) String reviewPolicy,
        @NotNull @Min(1) @Max(AssessmentLimits.MAX_ATTEMPTS) Integer maxAttempts,
        @NotNull @Min(0) @Max(AssessmentLimits.MAX_RETAKE_WAIT_HOURS) Integer retakeWaitHours,
        @NotNull Boolean allowRetakeAfterPass,
        @NotNull Boolean requireLessonsCompleted) {
    }

    public record PoolItem(
        @NotNull @Positive Long questionId,
        @Min(0) int sortNo,
        @Schema(description = "Điểm của câu trong đề; bỏ trống để dùng điểm mặc định của câu hỏi.")
        @DecimalMin("0.01") @DecimalMax("9999.99") @Digits(integer = 4, fraction = 2) BigDecimal points) {
    }

    public record PoolRequest(
        @NotNull @Size(max = AssessmentLimits.MAX_POOL_SIZE) List<@Valid @NotNull PoolItem> items) {
    }

    public record BulkStatusRequest(
        @NotEmpty @Size(max = AssessmentLimits.MAX_BULK_IDS) List<@NotNull @Positive Long> ids,
        @NotNull @Pattern(regexp = ItemStatus.PATTERN) String status) {
    }

    public record BulkIdsRequest(
        @NotEmpty @Size(max = AssessmentLimits.MAX_BULK_IDS) List<@NotNull @Positive Long> ids) {
    }

    public record RegradeRequest(
        @Schema(description = "Câu hỏi đã sửa đáp án đúng trong ngân hàng.") @NotNull @Positive Long questionId,
        @NotBlank @Size(max = AssessmentLimits.MAX_REASON_LENGTH) String reason,
        @Schema(description = "TRUE (mặc định) chỉ xem trước; FALSE áp dụng các thay đổi có lợi cho học viên.")
        Boolean dryRun) {
    }

    public record VoidRequest(@NotBlank @Size(max = AssessmentLimits.MAX_REASON_LENGTH) String reason) {
    }

    public record GrantRequest(
        @Schema(description = "Mã cán bộ của học viên được cấp; phải có lần học của khóa.")
        @NotBlank @Size(max = 20) String userId,
        @NotNull @Min(1) @Max(AssessmentLimits.MAX_EXTRA_ATTEMPTS) Integer extraAttempts,
        @NotBlank @Size(max = AssessmentLimits.MAX_REASON_LENGTH) String reason) {
    }

    /** PATCH đáp án: bỏ qua field để giữ nguyên; field ngoài whitelist bị từ chối. */
    @Getter
    @NoArgsConstructor
    public static class AnswerPatch {
        @JsonIgnore
        private final Set<String> suppliedFields = new HashSet<>();

        @Schema(description = "ID phương án đã chọn trong đề đã phát; [] để bỏ chọn. Bỏ qua để giữ nguyên.")
        @Size(max = AssessmentLimits.MAX_OPTIONS)
        private List<@NotNull @Positive Long> selectedOptionIds;

        @Schema(description = "Đánh dấu xem lại sau. Bỏ qua để giữ nguyên.")
        private Boolean flagged;

        @JsonSetter("selectedOptionIds")
        public void setSelectedOptionIds(List<Long> value) {
            suppliedFields.add("selectedOptionIds");
            selectedOptionIds = value;
        }

        @JsonSetter("flagged")
        public void setFlagged(Boolean value) {
            suppliedFields.add("flagged");
            flagged = value;
        }

        @JsonIgnore
        public boolean has(String field) {
            return suppliedFields.contains(field);
        }

        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new UnsupportedFieldException(field);
        }
    }
}
