package vn.com.coreacademy.assessment.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.dto.AssessmentViews.CompetencyReview;
import vn.com.coreacademy.assessment.service.CompetencyReviewService;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/me/competency", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CompetencyReviewController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CompetencyReviewController {
    private final CompetencyReviewService review;

    @GetMapping("/review")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Gợi ý ôn tập theo năng lực yếu",
        description = """
            Năng lực yếu của caller (percent < 70, cùng số với me/competency; percent rỗng bị bỏ), yếu nhất trước, kèm
            tối đa 3 bài mà câu sai đến từ.
            - Bài xếp theo số câu sai trong 90 ngày gần đây: câu sai của lượt thi đã chấm (bỏ trống tính là sai) cộng
              câu sai của câu hỏi nhanh.
            - Bài bị bỏ khi hai câu trả lời câu hỏi nhanh gần nhất của caller trong bài, cho năng lực đó, đều đúng.
            - Chỉ bài caller còn mở được.
            - competencyId: chỉ năng lực đó, và chỉ khi nó đang yếu. limit: số năng lực tối đa, mặc định 3, tối đa 10.
            """
    )
    public BaseResponse<List<CompetencyReview>> review(
        @RequestParam(required = false) @Positive Long competencyId,
        @RequestParam(required = false) @Min(1) @Max(AssessmentLimits.REVIEW_MAX_COMPETENCIES) Integer limit) {
        return BaseResponse.ok(review.review(competencyId, limit));
    }
}
