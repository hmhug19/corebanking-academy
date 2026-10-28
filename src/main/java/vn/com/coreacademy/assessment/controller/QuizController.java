package vn.com.coreacademy.assessment.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.service.QuizService;
import vn.com.coreacademy.assessment.service.RegradeService;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/quizzes", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "QuizController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class QuizController {
    private final QuizService quizzes;
    private final RegradeService regrades;

    @PostMapping("/{id}/regrade")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Chấm lại bài kiểm tra sau khi sửa đáp án",
        description = """
            Chấm lại các lượt đã nộp của đề theo đáp án đúng hiện tại của questionId, khớp theo ID phương án.
            - dryRun=true (mặc định) chỉ xem trước từng lượt; false áp dụng và ghi lịch sử, audit cả đợt.
            - Chỉ áp dụng lượt tăng điểm (INCREASED); lượt giảm điểm chỉ báo, không ghi. Lượt đang làm/VOIDED bỏ qua.
            - Lượt có phương án đã đổi ID hoặc câu đổi loại được báo SKIPPED, không đoán đáp án.
            """
    )
    public BaseResponse<AssessmentViews.RegradeResult> regrade(@PathVariable @Positive Long id,
        @Valid @RequestBody AssessmentRequests.RegradeRequest request) {
        return BaseResponse.ok(regrades.regrade(id, request));
    }

    @GetMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách bài kiểm tra của khóa",
        description = """
            Caller phải có quyền quản lý khóa; kèm số lượt đã chấm, số lượt đạt và kích thước pool.
            """
    )
    public BaseResponse<PageResponse<AssessmentViews.QuizSummary>> search(@RequestParam @Positive Long courseId,
        @Parameter(description = "Tìm trong tên bài, tối đa 100 ký tự.") @RequestParam(required = false) String q,
        @Parameter(description = "DRAFT | PUBLISHED | CLOSED") @RequestParam(required = false) String status,
        @Parameter(description = "PRACTICE | FINAL_EXAM") @RequestParam(required = false) String quizType,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @Parameter(description = "id | title | updateTime, kèm ,asc hoặc ,desc") @RequestParam(defaultValue = "id,desc")
        String sort) {
        return BaseResponse.ok(quizzes.search(courseId, q, status, quizType, page, size, sort));
    }

    @GetMapping("/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Chi tiết cấu hình bài kiểm tra",
        description = """
            Cấu hình đề và pool câu hỏi; activePoolSize là số câu ACTIVE dùng để kiểm tra điều kiện xuất bản.
            """
    )
    public BaseResponse<AssessmentViews.QuizDetail> get(@PathVariable @Positive Long id) {
        return BaseResponse.ok(quizzes.get(id));
    }

    @PostMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạo bài kiểm tra",
        description = """
            Tạo đề DRAFT cho courseId; học viên chưa thấy cho tới khi xuất bản.
            - Mỗi khóa tối đa một đề FINAL_EXAM.
            """
    )
    public BaseResponse<AssessmentViews.QuizDetail> create(@Valid @RequestBody AssessmentRequests.QuizRequest request) {
        return BaseResponse.ok(quizzes.create(request));
    }

    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cập nhật cấu hình bài kiểm tra",
        description = """
            Thay toàn bộ cấu hình; gửi expectedVersion là rowVersion vừa đọc.
            - Lượt đang làm/đã làm giữ thông số đã chụp lúc bắt đầu; thay đổi chỉ áp dụng cho lượt mới.
            - Đề đã xuất bản vẫn phải đủ câu ACTIVE cho questionCount.
            """
    )
    public BaseResponse<AssessmentViews.QuizDetail> update(@PathVariable @Positive Long id,
        @RequestParam @Min(0) Long expectedVersion, @Valid @RequestBody AssessmentRequests.QuizRequest request) {
        return BaseResponse.ok(quizzes.update(id, expectedVersion, request));
    }

    @PutMapping("/{id}/questions")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Thay pool câu hỏi của bài kiểm tra",
        description = """
            Thay toàn bộ pool; câu phải thuộc cùng khóa, chưa xóa và không lặp. Gửi expectedVersion của đề.
            - points bỏ trống để dùng điểm mặc định của câu hỏi.
            """
    )
    public BaseResponse<AssessmentViews.QuizDetail> replacePool(@PathVariable @Positive Long id,
        @RequestParam @Min(0) Long expectedVersion, @Valid @RequestBody AssessmentRequests.PoolRequest request) {
        return BaseResponse.ok(quizzes.replacePool(id, expectedVersion, request));
    }

    @PostMapping("/{id}/publish")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xuất bản bài kiểm tra",
        description = """
            Chuyển DRAFT/CLOSED sang PUBLISHED khi số câu ACTIVE trong pool đủ questionCount.
            - closeAt (nếu có) phải ở tương lai; gọi lại khi đã PUBLISHED trả về cấu hình hiện tại.
            """
    )
    public BaseResponse<AssessmentViews.QuizDetail> publish(@PathVariable @Positive Long id,
        @Valid @RequestBody AssessmentRequests.VersionRequest request) {
        return BaseResponse.ok(quizzes.publish(id, request));
    }

    @PostMapping("/{id}/close")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Đóng bài kiểm tra",
        description = """
            Chuyển PUBLISHED sang CLOSED; học viên không bắt đầu được lượt mới nhưng vẫn xem được kết quả.
            - Lượt đang làm được tự nộp ngay với đáp án đã lưu (AUTO_SUBMITTED, QUIZ_CLOSED).
            - Gọi lại khi đã CLOSED trả về cấu hình hiện tại; mở lại bằng publish.
            """
    )
    public BaseResponse<AssessmentViews.QuizDetail> close(@PathVariable @Positive Long id,
        @Valid @RequestBody AssessmentRequests.VersionRequest request) {
        return BaseResponse.ok(quizzes.close(id, request));
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xóa bài kiểm tra",
        description = """
            Xóa mềm đề chưa từng có lượt thi; đề đã có lượt trả ASSESSMENT-QUIZ_IN_USE và chỉ được đóng.
            """
    )
    public BaseResponse<Void> delete(@PathVariable @Positive Long id, @RequestParam @Min(0) Long expectedVersion) {
        quizzes.delete(id, expectedVersion);
        return BaseResponse.ok();
    }

    @GetMapping("/{id}/preview")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xem thử đề",
        description = """
            Bốc một đề mẫu theo cấu hình hiện tại (kể cả DRAFT), kèm đáp án đúng và giải thích; không tạo lượt thi.
            - Mỗi lần gọi có thể ra đề khác nếu bật bốc ngẫu nhiên hoặc trộn câu/phương án.
            """
    )
    public BaseResponse<AssessmentViews.QuizPreview> preview(@PathVariable @Positive Long id) {
        return BaseResponse.ok(quizzes.preview(id));
    }
}
