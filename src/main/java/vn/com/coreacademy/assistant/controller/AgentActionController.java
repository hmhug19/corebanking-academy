package vn.com.coreacademy.assistant.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assistant.action.AgentActionService;
import vn.com.coreacademy.assistant.dto.ActionResultView;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/assistant/agent/actions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant agent")
@StandardApiErrors
@RequiredArgsConstructor
public class AgentActionController {
    private final AgentActionService actions;

    @PostMapping("/{actionId}/confirm")
    @Operation(
        summary = "Xác nhận một thay đổi do trợ lý đề xuất",
        description = """
            Chạy thay đổi bằng quyền của chính người gọi, qua cùng service với màn hình tương ứng.
            - Mỗi yêu cầu chỉ chạy một lần; chỉ người tạo mới xác nhận được.
            - ASSISTANT-ACTION_NOT_FOUND: không có, hết hạn (10 phút), đã xử lý hoặc của người khác.
            - ASSISTANT-ACTION_STALE: dữ liệu đã đổi từ lúc đề xuất.
            """
    )
    public ResponseEntity<BaseResponse<ActionResultView>> confirm(@PathVariable String actionId) {
        return ResponseEntity.ok(BaseResponse.ok(actions.confirm(actionId)));
    }

    @PostMapping("/{actionId}/cancel")
    @Operation(
        summary = "Huỷ một thay đổi do trợ lý đề xuất",
        description = """
            Bỏ yêu cầu mà không chạy.
            - ASSISTANT-ACTION_NOT_FOUND: không có, hết hạn, đã xử lý hoặc của người khác.
            """
    )
    public ResponseEntity<BaseResponse<Void>> cancel(@PathVariable String actionId) {
        actions.cancel(actionId);
        return ResponseEntity.ok(BaseResponse.ok());
    }
}
