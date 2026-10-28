package vn.com.coreacademy.community.controller;

import jakarta.validation.Valid;
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
// import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.AiDtos.AIAnswerView;
import vn.com.coreacademy.community.dto.AiDtos.AIFeedbackView;
import vn.com.coreacademy.community.dto.AiDtos.AISourceView;
import vn.com.coreacademy.community.dto.AiDtos.AnswerRequest;
import vn.com.coreacademy.community.dto.AiDtos.Feedback;
import vn.com.coreacademy.community.service.CommunityAiService;

// Tạm dừng C126–C128 theo yêu cầu sản phẩm: không đăng ký bean/HTTP route hoặc hiển thị trên Swagger.
// Khi mở lại, khôi phục import và @RestController, đồng thời cập nhật kiểm thử và contract đang hoạt động.
// @RestController
@RequestMapping(value = "/api/v1/community/ai", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community AI")
public class CommunityAiController {
    private final CommunityAiService ai;

    @PostMapping("/answers")
    @Operation(summary = "Tra cứu AI có nguồn theo quyền hiện tại", description = """
        Trả lời từ Community, Learning và Knowledge bằng Ollama đã cấu hình.
        - Không lưu prompt/answer. Retry cùng requestId không chạy model hai lần.
        - resultRetained=false nghĩa là câu trả lời không còn trong cache RAM.
        """)
    @ApiResponse(responseCode = "200", description = "Lượt AI và citations đã kiểm quyền lại.",
        useReturnTypeSchema = true)
    public BaseResponse<AIAnswerView> answer(@Valid @RequestBody AnswerRequest request) {
        return BaseResponse.ok(ai.answer(request));
    }

    @GetMapping("/turns/{turnId}/sources")
    @Operation(summary = "Đọc nguồn của lượt AI")
    @ApiResponse(responseCode = "200", description = "Chỉ chủ turn và nguồn còn quyền hiện tại.",
        useReturnTypeSchema = true)
    public BaseResponse<PageResponse<AISourceView>> sources(@PathVariable @Positive Long turnId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(ai.sources(turnId, page, size));
    }

    @PutMapping("/turns/{turnId}/feedback")
    @Operation(summary = "Đánh giá câu trả lời AI")
    @ApiResponse(responseCode = "200", description = "SET một feedback của chủ turn, có version.",
        useReturnTypeSchema = true)
    public BaseResponse<AIFeedbackView> feedback(@PathVariable @Positive Long turnId,
        @Valid @RequestBody Feedback request) {
        return BaseResponse.ok(ai.feedback(turnId, request));
    }
}
