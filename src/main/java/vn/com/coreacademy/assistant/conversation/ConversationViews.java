package vn.com.coreacademy.assistant.conversation;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.assistant.dto.PendingActionView;
import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

public final class ConversationViews {
    private ConversationViews() {
    }

    @Schema(description = "Một dòng trong danh sách hội thoại, mới nhất trước.")
    public record ConversationSummary(
        long id,
        String title,
        @Schema(description = "Lần hỏi gần nhất.") Instant lastActivityTime,
        Instant createTime,
        @Schema(description = "Bài học mà hội thoại được mở từ đó; bỏ khi hội thoại không gắn bài học.")
        @JsonInclude(JsonInclude.Include.NON_NULL) LessonLabel context) {
    }

    @Schema(description = "Bài học gắn với hội thoại; label là \"Khóa › Bài\" lúc mở hội thoại.")
    public record LessonLabel(long courseId, long revisionId, long lessonId, String label) {
    }

    @Schema(description = "Hội thoại kèm các lượt, cũ nhất trước.")
    public record ConversationDetail(
        long id,
        String title,
        Instant lastActivityTime,
        Instant createTime,
        @JsonInclude(JsonInclude.Include.NON_NULL) LessonLabel context,
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
        @Schema(description = "Thẻ xác nhận của lượt kèm kết cục thật; null nếu lượt không đề xuất thay đổi.")
        ActionState action,
        @Schema(description = "Đánh giá của người dùng cho lượt; null nếu chưa đánh giá.") FeedbackView feedback,
        @Schema(description = "done, error hoặc stopped.") String status,
        Instant createTime) {
    }

    @Schema(description = "Thay đổi dữ liệu mà một lượt đã đề xuất, với trạng thái hiện tại.")
    public record ActionState(
        String id,
        String tool,
        String title,
        List<PendingActionView.Change> changes,
        @Schema(description = "pending (còn chờ), confirmed, cancelled, expired (hết hạn chưa trả lời) hoặc failed "
            + "(đã xác nhận nhưng công cụ từ chối).") String status,
        Instant expiresAt,
        @Schema(description = "Kết quả hiện cho người dùng sau khi xác nhận, hoặc lý do bị từ chối; null khi chưa có.")
        String message) {
    }

    @Schema(description = "Đánh giá một câu trả lời.")
    public record FeedbackRequest(
        @Schema(description = "true = hữu ích (👍), false = chưa tốt (👎).") boolean helpful,
        @Schema(description = "Chỉ khi helpful=false: irrelevant, incorrect, outdated, missing_source hoặc other.")
        String reasonCode,
        @Schema(description = "Ghi chú tuỳ chọn, tối đa 2000 ký tự.") String note) {
    }

    @Schema(description = "Đánh giá đã lưu của một lượt.")
    public record FeedbackView(boolean helpful, String reasonCode, String note) {
    }

    @Schema(description = "Đổi tên hội thoại.")
    public record RenameRequest(
        @Schema(description = "Từ 1 đến 80 ký tự sau khi bỏ khoảng trắng hai đầu.") String title) {
    }
}
