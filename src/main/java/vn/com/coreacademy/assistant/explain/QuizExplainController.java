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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping("/api/v1/assistant/quiz-attempts")
@Tag(name = "Assistant quiz explanation")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class QuizExplainController {
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final QuizExplainService explain;

    @PostMapping(value = "/{attemptId}/questions/{displayOrder}/explain",
        produces = {MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson"})
    @Operation(
        summary = "Giải thích một câu của lượt thi đã nộp (stream)",
        description = """
            Trả application/x-ndjson cùng các sự kiện như trợ lý nhanh (conversation, delta, done có citations, error).
            - Chỉ lượt thi của chính người gọi, đã nộp, và khi reviewPolicy của đề đang cho xem đáp án (cùng điều kiện với
              review trong kết quả); ngược lại là envelope ASSESSMENT-REVIEW_NOT_AVAILABLE hoặc ASSESSMENT-NOT_FOUND
              trước khi stream bắt đầu.
            - displayOrder là thứ tự câu trong review của kết quả.
            - Tính vào giới hạn câu hỏi mỗi giờ và được lưu thành một hội thoại như mọi lượt khác.
            """
    )
    public ResponseEntity<StreamingResponseBody> explain(@PathVariable @Positive Long attemptId,
                                                         @PathVariable @Positive Integer displayOrder) {
        StreamingResponseBody body = explain.stream(attemptId, displayOrder);
        return ResponseEntity.ok().contentType(NDJSON).header("X-Accel-Buffering", "no").body(body);
    }
}
