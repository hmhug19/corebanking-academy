package vn.com.coreacademy.learning.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningFeedbackRequests.RateLesson;
import vn.com.coreacademy.learning.dto.LearningFeedbackRequests.SendFeedback;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.CommentView;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.FeedbackView;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.LessonFeedbackView;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.RatingResult;
import vn.com.coreacademy.learning.service.LearningFeedbackService;

@RestController
@RequestMapping(value = "/api/v1/me/learning", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningFeedbackController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningFeedbackController {
    private final LearningFeedbackService feedback;

    @GetMapping("/lessons/{lessonId}/feedback")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Phản hồi của bài học",
        description = """
            Điểm đánh giá của bài, số sao của người gọi, trang đầu nhận xét công khai và câu hỏi, báo nội dung của họ.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            - Bài học phải thuộc phiên bản của một lần học của người gọi, nếu không là LEARNING-NOT_FOUND.
            - rating là null khi bài chưa có đánh giá; myStars là null khi người gọi chưa đánh giá.
            - comments là trang đầu (20) của mọi nhận xét sống, mới nhất trước, kèm tên người gửi và cờ mine.
            - mine là câu hỏi và báo nội dung của người gọi, mới nhất trước; của người khác không bao giờ có mặt.
            """
    )
    public BaseResponse<LessonFeedbackView> feedback(@PathVariable @Positive Long lessonId) {
        return BaseResponse.ok(feedback.feedback(lessonId));
    }

    @GetMapping("/lessons/{lessonId}/comments")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Nhận xét của bài học",
        description = """
            Nhận xét công khai của bài, mới nhất trước, cho nút xem thêm.
            - Quyền vào bài như phản hồi của bài học.
            - page mặc định 0, size mặc định 20 và tối đa 100; ngoài khoảng đó là CORE-VALIDATION_FAILED.
            """
    )
    public BaseResponse<PageResponse<CommentView>> comments(
        @PathVariable @Positive Long lessonId,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "" + LearningLimits.FEEDBACK_PAGE_SIZE) @Min(1)
        @Max(LearningLimits.MAX_COMMENT_PAGE_SIZE) int size) {
        return BaseResponse.ok(feedback.comments(lessonId, page, size));
    }

    @PostMapping("/lessons/{lessonId}/feedback")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Gửi nhận xét, câu hỏi hoặc báo nội dung",
        description = """
            Nhận xét hiện công khai cho học viên của bài; câu hỏi và báo nội dung chỉ người gửi thấy.
            - kind là comment, question hoặc report; mọi loại gửi kèm vị trí neo như ghi chú (none khi không có).
            - Khối không thuộc bài, sai loại hoặc vị trí ngoài tài nguyên là LEARNING-INVALID_REFERENCE.
            - text sau khi cắt khoảng trắng dài 5 đến 1000 ký tự, nếu không là CORE-VALIDATION_FAILED.
            - Nhận xét không có status; câu hỏi và báo nội dung bắt đầu ở new.
            - Tối đa 20 lần gửi mỗi người mỗi bài mỗi ngày học (Asia/Ho_Chi_Minh), vượt là LEARNING-FEEDBACK_LIMIT.
            """
    )
    public BaseResponse<FeedbackView> send(@PathVariable @Positive Long lessonId,
                                           @Valid @RequestBody SendFeedback request) {
        return BaseResponse.ok(feedback.send(lessonId, request));
    }

    @PutMapping("/lessons/{lessonId}/rating")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Đánh giá bài học",
        description = """
            Đặt số sao của người gọi cho bài học; gọi lại thì thay đánh giá cũ, mỗi người một đánh giá mỗi bài.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            - Bài học phải thuộc phiên bản của một lần học của người gọi, nếu không là LEARNING-NOT_FOUND.
            - stars là số nguyên từ 1 đến 5, nếu không là CORE-VALIDATION_FAILED.
            - Trả average (làm tròn một chữ số thập phân), count của bài và myStars.
            """
    )
    public BaseResponse<RatingResult> rate(@PathVariable @Positive Long lessonId,
                                           @Valid @RequestBody RateLesson request) {
        return BaseResponse.ok(feedback.rate(lessonId, request));
    }
}
