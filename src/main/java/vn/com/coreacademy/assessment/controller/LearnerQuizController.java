package vn.com.coreacademy.assessment.controller;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.service.AssessmentReportService;
import vn.com.coreacademy.assessment.service.QuizAttemptService;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearnerQuizController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearnerQuizController {
    private final QuizAttemptService attempts;
    private final AssessmentReportService reports;

    @GetMapping("/competency")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Năng lực của tôi",
        description = """
            Tổng hợp theo nhóm năng lực trên mọi lượt đã chấm của caller; courseId tùy chọn để xem theo khóa.
            """
    )
    public BaseResponse<List<AssessmentViews.MyCompetency>> competency(
        @RequestParam(required = false) @Positive Long courseId) {
        return BaseResponse.ok(reports.myCompetency(courseId));
    }

    @GetMapping("/courses/{courseId}/quizzes")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Bài kiểm tra của khóa học",
        description = """
            Các đề đã xuất bản của khóa kèm lượt còn lại và lý do chưa thể làm bài, đề cuối khóa (FINAL_EXAM) xếp sau cùng.
            - Caller phải có lần học hợp lệ của khóa; đề DRAFT, CLOSED hoặc đã xóa không hiện.
            - canStart/blockReason/availableAt giống màn giới thiệu của từng đề.
            - lessonId là bài quiz trỏ tới đề trong phiên bản caller đã nhận; null nếu chưa gắn vào bài nào.
            """
    )
    public BaseResponse<List<AssessmentViews.LearnerQuizCard>> courseQuizzes(
        @PathVariable @Positive Long courseId) {
        return BaseResponse.ok(attempts.courseQuizzes(courseId));
    }

    @GetMapping("/quizzes/{quizId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Giới thiệu bài kiểm tra",
        description = """
            Cấu hình công khai, lượt còn lại, lượt đang làm dở và lý do chưa thể làm bài.
            - Caller phải có lần học hợp lệ của khóa; đề DRAFT trả không tìm thấy.
            - canStart=true khi được bắt đầu lượt mới hoặc tiếp tục runningAttemptId.
            """
    )
    public BaseResponse<AssessmentViews.QuizIntro> intro(@PathVariable @Positive Long quizId) {
        return BaseResponse.ok(attempts.intro(quizId));
    }

    @PostMapping("/quizzes/{quizId}/attempts")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Bắt đầu hoặc tiếp tục làm bài",
        description = """
            Trả lại lượt đang làm còn hạn (kể cả đổi thiết bị) hoặc tạo lượt mới; gọi lặp không tạo hai lượt.
            - Đề trả về không kèm đáp án đúng và giải thích; dùng serverTime/deadlineAt để đếm ngược.
            - Lượt cũ đã quá hạn được tự nộp trước khi xét lượt mới.
            """
    )
    public BaseResponse<AssessmentViews.AttemptSession> start(@PathVariable @Positive Long quizId,
        HttpServletRequest request) {
        return BaseResponse.ok(attempts.start(quizId, request.getRemoteAddr(),
            request.getHeader(HttpHeaders.USER_AGENT)));
    }

    @GetMapping("/quizzes/{quizId}/attempts")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Lịch sử thi của tôi theo bài kiểm tra",
        description = """
            Mọi lượt của caller theo thứ tự, kèm tổng hợp BEST/LAST và lượt còn lại.
            """
    )
    public BaseResponse<AssessmentViews.AttemptHistory> history(@PathVariable @Positive Long quizId) {
        return BaseResponse.ok(attempts.history(quizId));
    }

    @GetMapping("/quiz-attempts")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tất cả lượt thi của tôi",
        description = """
            Lượt thi của caller qua mọi bài kiểm tra, dùng cho trang Tiến độ; có thể lọc theo đề và trạng thái.
            - graded=true chỉ trả lượt đã chấm (SUBMITTED, AUTO_SUBMITTED), bỏ lượt đang làm và VOIDED vốn chưa có điểm.
            """
    )
    public BaseResponse<PageResponse<AssessmentViews.MyAttemptRow>> mine(
        @RequestParam(required = false) @Positive Long quizId,
        @Parameter(description = "IN_PROGRESS | SUBMITTED | AUTO_SUBMITTED | VOIDED") @RequestParam(required = false)
        String status,
        @Parameter(description = "true chỉ lấy lượt đã chấm (SUBMITTED, AUTO_SUBMITTED); kết hợp với status theo AND")
        @RequestParam(required = false) Boolean graded,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @Parameter(description = "id | startedAt | submittedAt, kèm ,asc hoặc ,desc")
        @RequestParam(defaultValue = "startedAt,desc") String sort,
        @RequestParam(required = false) @Size(max = 100) String q,
        @RequestParam(required = false) Boolean passed,
        @RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to) {
        return BaseResponse.ok(attempts.mine(quizId, status, graded, page, size, sort, q, passed, from, to));
    }

    @GetMapping(value = "/quiz-attempts/export", produces = {"text/csv", MediaType.APPLICATION_JSON_VALUE})
    @Operation(
        summary = "Xuất lượt thi của tôi ra CSV",
        description = """
            Cùng bộ lọc với danh sách, tối đa 10.000 dòng.
            - UTF-8 BOM, vô hiệu hóa công thức bảng tính; không bọc BaseResponse khi thành công.
            """
    )
    public ResponseEntity<byte[]> exportMine(@RequestParam(required = false) @Positive Long quizId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) Boolean graded,
                                             @RequestParam(defaultValue = "startedAt,desc") String sort) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=quiz-attempts.csv")
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(attempts.exportMine(quizId, status, graded, sort));
    }

    @GetMapping("/quiz-attempts/{attemptId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tải lại lượt đang làm",
        description = """
            Đề đã phát, đáp án đã lưu và cờ xem lại; dùng khi tải lại trang hoặc đổi thiết bị.
            - expired=true nghĩa là đã quá hạn nhưng chưa chấm; FE gọi submit để nhận kết quả.
            """
    )
    public BaseResponse<AssessmentViews.AttemptSession> session(@PathVariable @Positive Long attemptId) {
        return BaseResponse.ok(attempts.session(attemptId));
    }

    @PatchMapping(value = "/quiz-attempts/{attemptId}/questions/{attemptQuestionId}",
        consumes = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Lưu đáp án một câu",
        description = """
            Gửi selectedOptionIds và/hoặc flagged; field bỏ qua giữ nguyên, [] để bỏ chọn.
            - Chặn khi lượt đã nộp hoặc quá deadline cộng ân hạn; ID phương án phải thuộc đề đã phát.
            """
    )
    public BaseResponse<AssessmentViews.AnswerReceipt> saveAnswer(@PathVariable @Positive Long attemptId,
        @PathVariable @Positive Long attemptQuestionId, @Valid @RequestBody AssessmentRequests.AnswerPatch request) {
        return BaseResponse.ok(attempts.saveAnswer(attemptId, attemptQuestionId, request));
    }

    @PostMapping("/quiz-attempts/{attemptId}/submit")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Nộp bài",
        description = """
            Chấm phía server trên đề đã phát và đáp án đã lưu; client không gửi điểm.
            - Gọi lại sau khi đã nộp trả về đúng kết quả cũ; quá hạn được ghi nhận là tự nộp (TIMEOUT).
            """
    )
    public BaseResponse<AssessmentViews.AttemptResult> submit(@PathVariable @Positive Long attemptId) {
        return BaseResponse.ok(attempts.submit(attemptId));
    }

    @GetMapping("/quiz-attempts/{attemptId}/result")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Kết quả lượt thi",
        description = """
            Điểm, đạt/chưa đạt, kết quả theo nhóm năng lực và tổng hợp lượt.
            - review (đáp án đúng, giải thích) chỉ có khi reviewPolicy của đề cho phép.
            """
    )
    public BaseResponse<AssessmentViews.AttemptResult> result(@PathVariable @Positive Long attemptId) {
        return BaseResponse.ok(attempts.result(attemptId));
    }
}
