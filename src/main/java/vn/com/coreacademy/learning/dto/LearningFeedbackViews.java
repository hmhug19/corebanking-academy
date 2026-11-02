package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.learning.dto.LearningNoteViews.Anchor;

public final class LearningFeedbackViews {
    private LearningFeedbackViews() {
    }

    @Schema(description = "average là trung bình các đánh giá sống, làm tròn một chữ số thập phân; count là số lượng.")
    public record RatingSummary(BigDecimal average, long count) {
    }

    @Schema(description = "Điểm đánh giá của bài sau khi lưu và số sao của chính người gọi.")
    public record RatingResult(BigDecimal average, long count, int myStars) {
    }

    @Schema(description = "Nhận xét công khai; mine là true khi nhận xét do chính người gọi gửi.")
    public record CommentView(Long id, String authorName, String text, Anchor anchor, Instant createTime,
                              boolean mine) {
    }

    @Schema(description = "Câu hỏi hoặc báo nội dung của chính người gọi; status là null với nhận xét.")
    public record FeedbackView(Long id, String kind, String text, Anchor anchor, String status, String reply,
                               Instant replyTime, Instant createTime) {
    }

    @Schema(description = "rating là null khi bài chưa có đánh giá, myStars là null khi người gọi chưa đánh giá; "
        + "comments là trang đầu của nhận xét; mine là câu hỏi và báo nội dung của người gọi, mới nhất trước.")
    public record LessonFeedbackView(RatingSummary rating, Integer myStars, PageResponse<CommentView> comments,
                                     List<FeedbackView> mine) {
    }
}
