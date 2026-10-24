package vn.com.coreacademy.assistant.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Hội thoại gần nhất do app giữ, dạng văn bản; backend không giữ hội thoại.")
public record AgentRequest(
    @Schema(description = "Từ 1 đến 20 tin nhắn; tin nhắn cuối cùng phải của user. Không nhận kết quả công cụ.")
    List<AgentMessage> messages) {
}
