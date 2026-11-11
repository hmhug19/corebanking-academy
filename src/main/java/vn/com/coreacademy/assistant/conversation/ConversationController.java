package vn.com.coreacademy.assistant.conversation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assistant.agent.AssistantGate;
import vn.com.coreacademy.assistant.conversation.ConversationViews.ConversationDetail;
import vn.com.coreacademy.assistant.conversation.ConversationViews.ConversationSummary;
import vn.com.coreacademy.assistant.conversation.ConversationViews.RenameRequest;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.security.SecurityContextUtils;

@RestController
@RequestMapping(value = "/api/v1/assistant/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant conversations")
@StandardApiErrors
@RequiredArgsConstructor
public class ConversationController {
    private final AssistantGate gate;
    private final ConversationService conversations;

    @GetMapping
    @Operation(summary = "Danh sách hội thoại của tôi",
        description = "Mới nhất trước, phân trang; q tìm theo tên (không phân biệt hoa thường).")
    public BaseResponse<PageResponse<ConversationSummary>> list(
        @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        gate.requireAccess();
        return BaseResponse.ok(conversations.list(SecurityContextUtils.getUserId(), q, page, size));
    }

    @GetMapping("/{conversationId}")
    @Operation(summary = "Một hội thoại kèm các lượt",
        description = "Id của người khác trả ASSISTANT-CONVERSATION_NOT_FOUND, giống id không tồn tại.")
    public BaseResponse<ConversationDetail> get(@PathVariable long conversationId) {
        gate.requireAccess();
        return BaseResponse.ok(conversations.get(SecurityContextUtils.getUserId(), conversationId));
    }

    @PatchMapping("/{conversationId}")
    @Operation(summary = "Đổi tên hội thoại")
    public BaseResponse<ConversationSummary> rename(@PathVariable long conversationId,
                                                    @RequestBody RenameRequest request) {
        gate.requireAccess();
        return BaseResponse.ok(conversations.rename(SecurityContextUtils.getUserId(), conversationId,
            request.title()));
    }

    @DeleteMapping("/{conversationId}")
    @Operation(summary = "Xoá một hội thoại", description = "Xoá hẳn, cùng mọi lượt của nó.")
    public BaseResponse<Void> delete(@PathVariable long conversationId) {
        gate.requireAccess();
        conversations.delete(SecurityContextUtils.getUserId(), conversationId);
        return BaseResponse.ok();
    }

    @DeleteMapping
    @Operation(summary = "Xoá tất cả hội thoại của tôi", description = "Chỉ của người gọi; xoá hẳn.")
    public BaseResponse<Void> deleteAll() {
        gate.requireAccess();
        conversations.deleteAll(SecurityContextUtils.getUserId());
        return BaseResponse.ok();
    }
}
