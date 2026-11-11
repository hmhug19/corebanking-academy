package vn.com.coreacademy.knowledge.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Câu hỏi và, với câu hỏi tiếp theo trên /chat/stream, mã hội thoại để backend nạp các lượt trước.")
public record ChatRequest(
    @Schema(description = "Từ 1 đến 300 ký tự sau khi bỏ khoảng trắng hai đầu.", example = "CIF là gì?")
    @NotNull String question,
    @Schema(description = "Chỉ /chat/stream dùng. Bỏ trống để bắt đầu hội thoại mới; sự kiện đầu của stream trả mã đó. "
        + "Mã của người khác trả ASSISTANT-CONVERSATION_NOT_FOUND.") Long conversationId) {
}
