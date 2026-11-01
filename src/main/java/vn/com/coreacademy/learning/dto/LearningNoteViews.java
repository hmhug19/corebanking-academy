package vn.com.coreacademy.learning.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

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
}
