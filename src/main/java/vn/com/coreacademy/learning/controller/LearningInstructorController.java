package vn.com.coreacademy.learning.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.constants.LearningCodes.SearchMode;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningFeedbackRequests.ReplyFeedback;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.FeedbackView;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.InstructorFeedbackSummary;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.InstructorFeedbackView;
import vn.com.coreacademy.learning.service.LearningInstructorFeedbackService;
import vn.com.coreacademy.learning.service.LearningSearchService;

@RestController
@RequestMapping(value = "/api/v1/instructor", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningInstructorController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningInstructorController {
    private final LearningSearchService search;
    private final LearningInstructorFeedbackService feedback;

    @GetMapping("/courses")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách khóa học giảng viên",
        description = """
            Lọc owner/scope trước count và phân trang; số người được giao là số user khác nhau.
            """
    )
    public BaseResponse<PageResponse<LearningDetails.CourseCard>> courses(@RequestParam(required = false) String q,
        @RequestParam(required = false) Long fieldId, @RequestParam(required = false) Long programId,
        @RequestParam(required = false) Long moduleId, @RequestParam(required = false) String level,
        @RequestParam(required = false) String requirement, @RequestParam(required = false) String learningStatus,
        @RequestParam(required = false) String state, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "id,asc") String sort) {
        return BaseResponse.ok(search.search(SearchMode.MANAGE, q, fieldId, programId, moduleId, level, requirement,
            learningStatus, state, page, size, sort));
    }

    @GetMapping("/feedback")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Câu hỏi và báo nội dung của học viên",
        description = """
            Câu hỏi và báo nội dung trên các khóa người gọi quản lý, mới nhất trước.
            - Phạm vi như danh sách khóa giảng viên (toàn cục, đơn vị, nhóm hoặc khóa do mình tạo); người không quản
              lý khóa nào nhận trang rỗng.
            - status lọc theo new | answered | resolved; bỏ trống là mọi trạng thái.
            - Nhận xét công khai và đánh giá sao không có mặt ở đây.
            """
    )
    public BaseResponse<PageResponse<InstructorFeedbackView>> feedback(
        @RequestParam(required = false) @Pattern(regexp = "new|answered|resolved") String status,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return BaseResponse.ok(feedback.list(status, page, size));
    }

    @GetMapping("/feedback/summary")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Số câu hỏi chưa trả lời",
        description = """
            newCount là số câu hỏi và báo nội dung chưa được trả lời (status=new) trên các khóa người gọi quản lý;
            0 với người không quản lý khóa nào. Chuông thông báo dùng số này.
            """
    )
    public BaseResponse<InstructorFeedbackSummary> feedbackSummary() {
        return BaseResponse.ok(feedback.summary());
    }

    @PostMapping("/feedback/{feedbackId}/reply")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Trả lời câu hỏi hoặc báo nội dung",
        description = """
            Lưu nội dung trả lời và đặt status=answered, hoặc resolved khi resolve=true; trả lời lại thay nội dung cũ.
            - Người gọi phải quản lý đúng khóa chứa bài, nếu không là 403.
            - Nhận xét, đánh giá hoặc phản hồi đã xóa là LEARNING-NOT_FOUND.
            - reply sau khi cắt khoảng trắng đầu cuối dài 1 đến 1000 ký tự.
            """
    )
    public BaseResponse<FeedbackView> reply(@PathVariable @Positive Long feedbackId,
        @RequestBody @Valid ReplyFeedback request) {
        return BaseResponse.ok(feedback.reply(feedbackId, request));
    }

}
