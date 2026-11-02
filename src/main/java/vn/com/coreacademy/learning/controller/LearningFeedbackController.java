package vn.com.coreacademy.learning.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.LearningFeedbackRequests.RateLesson;
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
