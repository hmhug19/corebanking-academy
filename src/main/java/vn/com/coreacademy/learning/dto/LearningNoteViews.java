package vn.com.coreacademy.learning.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.common.dto.PageResponse;

public final class LearningNoteViews {
    private LearningNoteViews() {
    }

    /** blockId, page và second là null khi không áp dụng cho type: none không có cả ba, pdf có page, video có second. */
    public record Anchor(String type, Long blockId, Integer page, Integer second) {
    }

    public record NoteView(Long id, Long lessonId, Anchor anchor, String text, Instant createTime) {
    }

    public record BookmarkView(Long id, Long lessonId, Anchor anchor, String label, Instant createTime) {
    }

    @Schema(description = "saved=true kèm bookmark vừa tạo; saved=false khi đánh dấu cũ bị bỏ (bookmark là null).")
    public record BookmarkToggle(boolean saved, BookmarkView bookmark) {
    }

    /**
     * Một dòng của trang Đã lưu. kind là note hoặc bookmark: ghi chú có text và label là null, đánh dấu có label và
     * text là null. courseTitle, lessonTitle là null nếu bài học không còn tra được.
     */
    public record SavedItemView(String kind, Long id, Long lessonId, Anchor anchor, String text, String label,
                                Instant createTime, Long courseId, String courseTitle, String lessonTitle) {
    }

    public record SavedCounts(long notes, long bookmarks) {
    }

    @Schema(description = "page là trang của tab đang xem; counts là số ghi chú và đánh dấu còn sống của cả hai tab.")
    public record SavedPage(PageResponse<SavedItemView> page, SavedCounts counts) {
    }
}
