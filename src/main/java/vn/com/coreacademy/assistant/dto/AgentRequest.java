package vn.com.coreacademy.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Câu hỏi mới và, với câu hỏi tiếp theo, mã hội thoại; backend tự nạp các lượt trước đó.")
public record AgentRequest(
    @Schema(description = "Mã hội thoại của người gọi. Bỏ trống để bắt đầu hội thoại mới; sự kiện đầu của stream "
        + "trả mã đó. Mã của người khác trả ASSISTANT-CONVERSATION_NOT_FOUND.") Long conversationId,
    @Schema(description = "Từ 1 đến 500 ký tự sau khi bỏ khoảng trắng hai đầu.", example = "Mở màn hình khóa học")
    String message,
    @Schema(description = "Kỹ năng do app chọn (qa_tai_lieu, dieu_huong, ho_so, tro_chuyen); gợi ý đọc-chỉ do server đánh dấu (done.suggestionsReadOnly) được gửi với qa_tai_lieu. Bỏ trống để server tự phân loại; giá trị lạ hoặc không được phép bị bỏ qua.") String skill,
    @Schema(description = "Bài học đang xem; có thì trợ lý ưu tiên nội dung bài đó. Bài không đọc được: ASSISTANT-LESSON_NOT_FOUND.")
    LessonContext context,
    @Schema(description = "Tài liệu cá nhân người dùng vừa tải lên từ ô soạn tin để tạo khóa học; chỉ dùng với kỹ năng tao_khoa_hoc. "
        + "Không phải của người gọi thì bị bỏ qua khi tạo khóa.") Long attachmentDocumentId) {
    public AgentRequest(Long conversationId, String message, String skill, LessonContext context) {
        this(conversationId, message, skill, context, null);
    }

    public AgentRequest(Long conversationId, String message, String skill) {
        this(conversationId, message, skill, null, null);
    }
}
