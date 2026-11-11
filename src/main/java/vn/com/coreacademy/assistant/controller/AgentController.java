package vn.com.coreacademy.assistant.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.assistant.agent.AgentService;
import vn.com.coreacademy.assistant.dto.AgentRequest;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping("/api/v1/assistant/agent")
@Tag(name = "Assistant agent")
@StandardApiErrors
@RequiredArgsConstructor
public class AgentController {
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final AgentService agent;

    @PostMapping(value = "/stream", produces = {MediaType.APPLICATION_JSON_VALUE, "application/x-ndjson"})
    @Operation(
        summary = "Trợ lý nhanh (stream)",
        description = """
            Trả application/x-ndjson, mỗi dòng một sự kiện JSON; không bọc BaseResponse.
            - tool (công cụ bắt đầu chạy), nhiều delta (văn bản), navigate (app chuyển trang),
              action_required (thay đổi dữ liệu chờ xác nhận), done (answer và citations) hoặc error (lỗi giữa chừng).
            - Từ chối trước khi bắt đầu stream (request không hợp lệ, AI chưa sẵn sàng) là envelope JSON.
            - Hội thoại chỉ gồm văn bản user và assistant; backend không nhận kết quả công cụ từ client.
            - Client nên gửi Accept: application/x-ndjson, application/json.
            """
    )
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody AgentRequest request) {
        StreamingResponseBody body = agent.stream(request);
        return ResponseEntity.ok().contentType(NDJSON).header("X-Accel-Buffering", "no").body(body);
    }

    @PostMapping("/{conversationId}/cancel")
    @Operation(
        summary = "Dừng lượt đang trả lời",
        description = "Hủy cuộc gọi tới mô hình của lượt đang chạy của người dùng và trả chỗ ngay; lượt được lưu là đã dừng."
    )
    public ResponseEntity<Void> cancel(@PathVariable Long conversationId) {
        agent.cancel(conversationId);
        return ResponseEntity.noContent().build();
    }
}
