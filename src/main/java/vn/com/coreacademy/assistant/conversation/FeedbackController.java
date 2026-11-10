package vn.com.coreacademy.assistant.conversation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assistant.agent.AssistantGate;
import vn.com.coreacademy.assistant.conversation.ConversationViews.FeedbackRequest;
import vn.com.coreacademy.assistant.conversation.ConversationViews.FeedbackView;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.security.SecurityContextUtils;

@RestController
@RequestMapping(value = "/api/v1/assistant/turns", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant feedback")
@StandardApiErrors
@RequiredArgsConstructor
public class FeedbackController {
    private final AssistantGate gate;
    private final FeedbackService feedback;

    @PutMapping("/{turnId}/feedback")
    @Operation(summary = "Đánh giá một câu trả lời",
        description = "Ghi hoặc sửa 👍/👎 của chính chủ lượt. Id lượt của người khác trả ASSISTANT-TURN_NOT_FOUND, "
            + "giống id không tồn tại.")
    public BaseResponse<FeedbackView> put(@PathVariable long turnId, @RequestBody FeedbackRequest request) {
        gate.requireAccess();
        return BaseResponse.ok(feedback.put(SecurityContextUtils.getUserId(), turnId, request.helpful(),
            request.reasonCode(), request.note()));
    }
}
