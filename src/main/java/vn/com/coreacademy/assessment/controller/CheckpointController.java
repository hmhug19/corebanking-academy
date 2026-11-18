package vn.com.coreacademy.assessment.controller;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

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
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.service.CheckpointService;
import vn.com.coreacademy.assessment.service.CheckpointService.CheckpointResult;
import vn.com.coreacademy.assessment.service.CheckpointService.CheckpointView;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/me/lessons/{lessonId}/checkpoint", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CheckpointController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CheckpointController {
    private final CheckpointService service;

    @GetMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Câu hỏi nhanh cuối bài",
        description = """
            Tối đa 3 câu từ flashcard đã công bố của bài học; bài không có thẻ trả danh sách rỗng.
            - Câu hỏi không kèm đáp án đúng; đáp án chỉ trả sau khi trả lời.
            - Không tính điểm, XP hay tiến độ.
            """
    )
    public BaseResponse<CheckpointView> checkpoint(@PathVariable @Positive long lessonId) {
        return BaseResponse.ok(service.checkpoint(lessonId));
    }

    @PostMapping("/answers")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Trả lời câu hỏi nhanh",
        description = """
            Chấm trên server và trả đáp án đúng cùng giải thích.
            - Câu hỏi đã đổi hoặc không còn là câu hỏi nhanh của bài trả ASSESSMENT-CHECKPOINT_STALE.
            """
    )
    public BaseResponse<CheckpointResult> answer(@PathVariable @Positive long lessonId,
                                                 @Valid @RequestBody AnswerInput input) {
        return BaseResponse.ok(service.answer(lessonId, input.questionId(), input.questionVersion(),
            Set.copyOf(input.optionIds())));
    }

    public record AnswerInput(@NotNull @Positive Long questionId, @NotNull Long questionVersion,
                              @NotEmpty @Size(max = AssessmentLimits.MAX_OPTIONS) List<@NotNull Long> optionIds) {
    }
}
