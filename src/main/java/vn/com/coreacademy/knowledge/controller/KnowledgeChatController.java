package vn.com.coreacademy.knowledge.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.knowledge.dto.ChatRequest;
import vn.com.coreacademy.knowledge.dto.ChatViews.ChatAnswerView;
import vn.com.coreacademy.knowledge.service.KnowledgeChatService;

@RestController
@RequestMapping("/api/v1/knowledge/chat")
@Tag(name = "Knowledge chat")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class KnowledgeChatController {
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final KnowledgeChatService chat;

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Hỏi đáp trên kho tài liệu",
        description = """
            Trả một câu trả lời hoàn chỉnh, chỉ dựa trên các đoạn tìm được trong kho dùng chung.
            - Không có đoạn nào đủ liên quan: trả đúng câu "Không tìm thấy thông tin này trong tài liệu."
              và không trích dẫn.
            - Trích dẫn dựng từ metadata của đoạn (tệp, trang, dòng), không phân tích từ văn bản model.
            - Mỗi câu hỏi độc lập; backend không giữ hội thoại.
            """
    )
    public BaseResponse<ChatAnswerView> ask(@Valid @RequestBody ChatRequest request) {
        return BaseResponse.ok(chat.answer(request.question()));
    }

    @PostMapping(value = "/stream", produces = {MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson"})
    @Operation(
        summary = "Hỏi đáp trên kho tài liệu (stream)",
        description = """
            Trả application/x-ndjson, mỗi dòng một sự kiện JSON; không bọc BaseResponse.
            - retrieved (đã tìm xong), nhiều delta (văn bản), done (found và citations) hoặc error (lỗi giữa chừng).
            - Từ chối trước khi bắt đầu stream (câu hỏi không hợp lệ, AI chưa sẵn sàng) là envelope JSON như /chat.
            - Client nên gửi Accept: application/x-ndjson, application/json.
            """
    )
    public ResponseEntity<StreamingResponseBody> stream(@Valid @RequestBody ChatRequest request) {
        StreamingResponseBody body = chat.stream(request.question());
        return ResponseEntity.ok().contentType(NDJSON).header("X-Accel-Buffering", "no").body(body);
    }
}
