package vn.com.coreacademy.assessment.controller;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.service.QuizAttemptAdminService;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "QuizAttemptAdminController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class QuizAttemptAdminController {
    private final QuizAttemptAdminService attempts;

    @GetMapping("/quizzes/{quizId}/attempts")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách lượt thi của bài kiểm tra",
        description = """
            Mọi lượt của đề, gồm cả lượt đang làm và VOIDED; caller phải quản lý khóa chứa đề.
            - from/to lọc theo thời điểm bắt đầu, ISO-8601 có offset hoặc Z.
            """
    )
    public BaseResponse<PageResponse<AssessmentViews.AdminAttemptRow>> search(@PathVariable @Positive Long quizId,
        @Parameter(description = "Mã cán bộ học viên.") @RequestParam(required = false) String userId,
        @Parameter(description = "IN_PROGRESS | SUBMITTED | AUTO_SUBMITTED | VOIDED") @RequestParam(required = false)
        String status,
        @RequestParam(required = false) Boolean passed,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @Parameter(description = "id | startedAt | submittedAt | scorePercent, kèm ,asc hoặc ,desc")
        @RequestParam(defaultValue = "id,desc") String sort) {
        return BaseResponse.ok(attempts.search(quizId, userId, status, passed, from, to, page, size, sort));
    }

    @GetMapping("/quiz-attempts/{attemptId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Chi tiết lượt thi cho quản trị",
        description = """
            Toàn bộ bài làm kèm đáp án đúng, giờ lưu, số lần đổi đáp án, IP/thiết bị để xử lý khiếu nại.
            """
    )
    public BaseResponse<AssessmentViews.AdminAttemptDetail> detail(@PathVariable @Positive Long attemptId) {
        return BaseResponse.ok(attempts.detail(attemptId));
    }

    @PostMapping("/quiz-attempts/{attemptId}/void")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Hủy lượt thi",
        description = """
            Chuyển lượt sang VOIDED với lý do bắt buộc; lượt không tính vào số lượt đã dùng và điểm.
            - Bài làm được giữ nguyên để đối chiếu; hủy lượt đang làm cho phép học viên bắt đầu lượt mới.
            """
    )
    public BaseResponse<AssessmentViews.AdminAttemptDetail> voidAttempt(@PathVariable @Positive Long attemptId,
        @Valid @RequestBody AssessmentRequests.VoidRequest request) {
        return BaseResponse.ok(attempts.voidAttempt(attemptId, request));
    }

    @PostMapping("/quiz-attempts/{attemptId}/force-submit")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Nộp hộ lượt thi",
        description = """
            Quản trị nộp lượt đang làm khi học viên gặp sự cố; chấm với đáp án đã lưu (SUBMITTED, ADMIN).
            - Lượt không còn IN_PROGRESS trả ASSESSMENT-CONFLICT.
            """
    )
    public BaseResponse<AssessmentViews.AdminAttemptDetail> forceSubmit(@PathVariable @Positive Long attemptId) {
        return BaseResponse.ok(attempts.forceSubmit(attemptId));
    }

    @GetMapping("/quizzes/{quizId}/grants")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Lịch sử cấp thêm lượt",
        description = """
            Các lần cấp thêm lượt của đề, mới nhất trước, gồm cả lần đã thu hồi (revoked=true).
            """
    )
    public BaseResponse<List<AssessmentViews.GrantView>> grants(@PathVariable @Positive Long quizId,
        @Parameter(description = "Lọc theo mã cán bộ học viên.") @RequestParam(required = false) String userId) {
        return BaseResponse.ok(attempts.grants(quizId, userId));
    }

    @GetMapping("/quizzes/{quizId}/exhausted-learners")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách thí sinh đã hết lượt thi",
        description = """
            Liệt kê theo từng bài kiểm tra, có tính lượt được cấp bổ sung còn hiệu lực.
            - Chỉ người quản lý khóa chứa bài kiểm tra được tra cứu.
            """
    )
    public BaseResponse<PageResponse<AssessmentViews.ExhaustedLearner>> exhaustedLearners(
        @PathVariable @Positive Long quizId,
        @Parameter(description = "Tìm theo một phần mã cán bộ.") @RequestParam(required = false) @Size(max = 20)
        String userId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(attempts.exhaustedLearners(quizId, userId, page, size));
    }

    @PostMapping("/quizzes/{quizId}/grants")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cấp thêm lượt thi",
        description = """
            Cấp riêng cho một học viên có lần học của khóa, kèm lý do; không đổi maxAttempts của đề.
            """
    )
    public BaseResponse<AssessmentViews.GrantView> grant(@PathVariable @Positive Long quizId,
        @Valid @RequestBody AssessmentRequests.GrantRequest request,
        @Parameter(description = "Chỉ cấp nếu học viên đã dùng hết lượt tại thời điểm lưu.")
        @RequestParam(defaultValue = "false") boolean requireExhausted) {
        return BaseResponse.ok(attempts.grant(quizId, request, requireExhausted));
    }

    @PostMapping("/quiz-attempt-grants/{grantId}/revoke")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Thu hồi lượt cấp thêm",
        description = """
            Đánh dấu thu hồi và giữ lịch sử; lượt đã dùng không bị hủy, số lượt còn lại không xuống dưới 0.
            """
    )
    public BaseResponse<AssessmentViews.GrantView> revoke(@PathVariable @Positive Long grantId) {
        return BaseResponse.ok(attempts.revoke(grantId));
    }
}
