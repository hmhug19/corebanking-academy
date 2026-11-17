package vn.com.coreacademy.assistant.drafting;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import vn.com.coreacademy.learning.dto.LearningRequests;

/** What the drafting endpoints receive and send. */
public final class DraftingViews {
    private DraftingViews() {
    }

    /** What the author said with the document; every field is optional. Admins may name the owner unit. */
    @Schema(description = "Thông tin gửi kèm tài liệu; mọi trường đều tùy chọn.")
    public record StartFields(
        @Schema(description = "Tên khóa học người dùng muốn, tối đa 255 ký tự") String title,
        @Schema(description = "Đối tượng học viên, tối đa 500 ký tự") String targetLearners,
        @Schema(description = "basic | intermediate | advanced") String level,
        @Schema(description = "Đơn vị sở hữu; giảng viên luôn là đơn vị của mình, quản trị chọn trong phạm vi quản lý")
        Long ownerOrgId) {
        public static final StartFields NONE = new StartFields(null, null, null, null);
    }

    public record SourceView(long documentId, String fileName, String status) {
    }

    @Schema(description = "Một bước đã tới của phiên; proposal theo từng bước (bước 1: BasicsProposal, bước 2: "
        + "OutlineProposal). lessonNotes chỉ có ở bước 2 đã chốt: mô tả và phần nguồn của từng bài theo id bài đã lưu.")
    public record StepView(int step, String state, JsonNode proposal, List<String> notes, String error,
                           OffsetDateTime updateTime,
                           @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode lessonNotes) {
    }

    @Schema(description = "Phiên Soạn cùng trợ lý; steps chỉ gồm các bước đã tới, tăng dần.")
    public record SessionView(long id, String status, int currentStep, Long courseId, Long revisionId,
                              Long conversationId, SourceView source, String error, List<StepView> steps,
                              OffsetDateTime createTime, OffsetDateTime updateTime) {
    }

    /** Chốt bước 1: the course create command built from the page's current values. */
    @Schema(description = "Chốt bước 1: lệnh tạo khóa học từ giá trị hiện có trên trang.")
    public record BasicsConfirm(@NotNull @Valid LearningRequests.CreateCourse course) {
    }

    /** Chốt bước 2: the draft (chapters and lessons) as the page holds it now. */
    @Schema(description = "Chốt bước 2: bản nháp (chương, bài) như trên trang; bài do trợ lý đề xuất không có nội dung.")
    public record OutlineConfirm(@NotNull @Valid LearningRequests.DraftRequest draft) {
    }
}
