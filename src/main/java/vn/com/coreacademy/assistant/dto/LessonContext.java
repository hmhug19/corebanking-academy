package vn.com.coreacademy.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Bài học mà người dùng đang xem khi mở trợ lý. Server kiểm quyền đọc bài; không có quyền thì từ chối.")
public record LessonContext(
    @Schema(description = "Khóa học của bài học.") Long courseId,
    @Schema(description = "Revision đang xuất bản của khóa học.") Long revisionId,
    @Schema(description = "Bài học.") Long lessonId) {
}
