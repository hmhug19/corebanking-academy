package vn.com.coreacademy.assistant.explain;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping("/api/v1/assistant/lessons")
@Tag(name = "Assistant self explanation")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class SelfExplainController {
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final SelfExplainService selfExplain;

    /** Body of {@code self-explain}: what the learner wrote, 50 to 1500 characters after trimming. */
    public record SelfExplainRequest(String text) {
    }

    @PostMapping(value = "/{lessonId}/self-explain",
        produces = {MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson"})
    @Operation(
        summary = "Đối chiếu phần người học tự giải thích với bài học (stream)",
        description = """
            Trả application/x-ndjson cùng các sự kiện như trợ lý nhanh (conversation, delta, done có citations, error).
            - Body {text}: 50–1500 ký tự sau khi cắt khoảng trắng; ngoài khoảng đó là lỗi xác thực trước khi stream.
            - Bài học không đọc được: LEARNING-NOT_FOUND; AI không sẵn sàng: ASSISTANT-AI_UNAVAILABLE; bài chưa có nội
              dung: ASSISTANT-LESSON_TEXT_UNAVAILABLE. Tất cả là envelope trước khi stream bắt đầu.
            - Câu trả lời có ba mục "Bạn đã nắm được", "Còn thiếu", "Cần xem lại (có thể hiểu chưa đúng)", chỉ dựa vào
              nội dung bài học, kèm citations.
            - Tính vào giới hạn câu hỏi mỗi giờ và được lưu thành một hội thoại như mọi lượt khác.
            """
    )
    public ResponseEntity<StreamingResponseBody> selfExplain(@PathVariable @Positive Long lessonId,
                                                             @RequestBody SelfExplainRequest request) {
        StreamingResponseBody body = selfExplain.stream(lessonId, request.text());
        return ResponseEntity.ok().contentType(NDJSON).header("X-Accel-Buffering", "no").body(body);
    }
}
