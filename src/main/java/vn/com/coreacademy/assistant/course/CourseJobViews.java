package vn.com.coreacademy.assistant.course;

import java.time.OffsetDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

public final class CourseJobViews {
    private CourseJobViews() {
    }

    @Schema(description = "Thông tin form khởi tạo. Đơn vị sở hữu do server quyết định.")
    public record Inputs(
        @Schema(description = "Đối tượng học viên, 1–500 ký tự.") String targetLearners,
        @Schema(description = "BEGINNER, INTERMEDIATE hoặc ADVANCED.") String level,
        @Schema(description = "Số chương mong muốn (1–20); cần chapters hoặc durationMinutes.") Integer chapters,
        @Schema(description = "Thời lượng mong muốn, phút (15–1200).") Integer durationMinutes,
        @Schema(description = "Chương trình đào tạo của khóa; do người dùng chọn, không do model.") Long programmeId,
        @Schema(description = "Năng lực gán cho câu hỏi; do người dùng chọn, không do model.") Long competencyId,
        @Schema(description = "Chỉ ADMIN/SUPER_ADMIN: đơn vị sở hữu khóa, trong phạm vi quản lý của họ. "
            + "Giảng viên không được gửi giá trị khác đơn vị của mình.") Long ownerOrgId,
        @Schema(description = "Tên khóa học nháp; bỏ trống thì AI đặt khi viết dàn ý.") String title,
        @Schema(description = "Mô tả khóa học; bỏ trống thì AI điền, có thì AI không đụng tới.") String description) {
        public Inputs(String targetLearners, String level, Integer chapters, Integer durationMinutes,
                      Long programmeId, Long competencyId, Long ownerOrgId) {
            this(targetLearners, level, chapters, durationMinutes, programmeId, competencyId, ownerOrgId, null, null);
        }
    }

    public record SourceView(Long documentId, String fileName, String status) {
    }

    public record StepView(String kind, String itemKey, String status, String errorMessage) {
    }

    @Schema(description = "Một job tạo khóa học: trạng thái, bước hiện tại, tiến độ và các đầu ra đã có.")
    public record JobView(
        long jobId,
        @Schema(description = "queued | running | waiting_approval | completed | failed | cancelled | stopped.")
        String status,
        String step,
        @Schema(description = "0–100.") int progress,
        @Schema(description = "Lý do thất bại, đọc được.") String error,
        Inputs inputs,
        long ownerOrgId,
        SourceView source,
        @Schema(description = "Khóa học nháp đã tạo; null khi chưa tạo.") Long createdDraftId,
        @Schema(description = "Các bước đã tạo ra cho job, theo thứ tự.") List<StepView> steps,
        OffsetDateTime createTime,
        OffsetDateTime updateTime,
        @Schema(description = "Khóa học nháp job tạo và ghi nội dung vào; có ngay khi tạo job.") Long courseId,
        @Schema(description = "Bản nháp của khóa học trên.") Long revisionId) {
    }

    public record JobSummary(long jobId, String status, String step, int progress, String error, String sourceFileName,
                             Long createdDraftId, OffsetDateTime createTime,
                             @Schema(description = "Khóa học nháp job ghi nội dung vào; để form khóa học tìm job của mình.")
                             Long courseId) {
    }

    public record LessonView(String key, String title, String status, String error,
                             com.fasterxml.jackson.databind.JsonNode blocks,
                             com.fasterxml.jackson.databind.JsonNode sourceNotes) {
    }

    public record QuestionsView(String lessonKey, String status, String error,
                                com.fasterxml.jackson.databind.JsonNode questions) {
    }

    @Schema(description = "Nội dung model đã tạo cho job, để tác giả rà soát. Chỉ chủ job đọc được.")
    public record ContentView(long jobId, String status, String step, int progress, String error,
                              com.fasterxml.jackson.databind.JsonNode outline, boolean outlineLocked,
                              List<LessonView> lessons, List<QuestionsView> questions, Long createdDraftId) {
    }

    public record DecisionRequest(String decision) {
    }
}
