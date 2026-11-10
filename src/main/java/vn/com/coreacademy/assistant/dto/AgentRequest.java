package vn.com.coreacademy.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Câu hỏi mới và, với câu hỏi tiếp theo, mã hội thoại; backend tự nạp các lượt trước đó.")
public record AgentRequest(
    @Schema(description = "Mã hội thoại của người gọi. Bỏ trống để bắt đầu hội thoại mới; sự kiện đầu của stream "
        + "trả mã đó. Mã của người khác trả ASSISTANT-CONVERSATION_NOT_FOUND.") Long conversationId,
    @Schema(description = "Từ 1 đến 500 ký tự sau khi bỏ khoảng trắng hai đầu.", example = "Mở màn hình khóa học")
    String message,
    @Schema(description = "Kỹ năng do app chọn (qa_tai_lieu, dieu_huong, ho_so, tro_chuyen). Bỏ trống để server tự phân loại; giá trị lạ hoặc không được phép bị bỏ qua.") String skill) {
}
