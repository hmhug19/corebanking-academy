package vn.com.coreacademy.assistant.conversation;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

public final class ConversationViews {
    private ConversationViews() {
    }

    @Schema(description = "Một dòng trong danh sách hội thoại, mới nhất trước.")
    public record ConversationSummary(
        long id,
        String title,
        @Schema(description = "Lần hỏi gần nhất.") Instant lastActivityTime,
        Instant createTime) {
    }

    @Schema(description = "Hội thoại kèm các lượt, cũ nhất trước.")
    public record ConversationDetail(
        long id,
        String title,
        Instant lastActivityTime,
        Instant createTime,
        List<TurnView> turns) {
    }

    @Schema(description = "Một lượt hỏi-đáp đã lưu.")
    public record TurnView(
        long id,
        @Schema(description = "AGENT (trợ lý nhanh) hoặc KNOWLEDGE (hỏi đáp tài liệu).") String surface,
        String question,
        @Schema(description = "null khi lượt lỗi hoặc bị dừng trước khi có câu trả lời.") String answer,
        List<CitationView> citations,
        List<String> toolNames,
        @Schema(description = "Màn hình trợ lý đã mở trong lượt; null nếu không có.") TurnRecord.Navigation navigation,
        @Schema(description = "Mã yêu cầu thay đổi dữ liệu do lượt đề xuất; null nếu không có.") String actionId,
        @Schema(description = "done, error hoặc stopped.") String status,
        Instant createTime) {
    }

    @Schema(description = "Đổi tên hội thoại.")
    public record RenameRequest(
        @Schema(description = "Từ 1 đến 80 ký tự sau khi bỏ khoảng trắng hai đầu.") String title) {
    }
}
