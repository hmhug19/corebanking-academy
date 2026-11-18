package vn.com.coreacademy.assistant.course;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assistant.agent.AssistantGate;
import vn.com.coreacademy.assistant.course.CourseJobViews.JobSummary;
import vn.com.coreacademy.assistant.course.CourseJobViews.JobView;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/assistant/course-jobs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant course generation")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CourseJobController {
    private final AssistantGate gate;
    private final CourseJobService jobs;

    /**
     * Retired: "Soạn cùng trợ lý" (/api/v1/assistant/drafting-sessions) replaced the old job start (ADR 0002). Jobs started
     * earlier still run to the end, and every read, review, cancel, stop and delete endpoint keeps working.
     */
    @PostMapping
    @Operation(
        summary = "Đã ngừng: bắt đầu tạo khóa học bằng AI theo cách cũ",
        description = """
            Không còn tạo job mới: mọi yêu cầu nhận HTTP 410 với messageCode ASSISTANT-COURSE_JOBS_RETIRED và không lưu gì.
            Dùng Soạn cùng trợ lý (POST /api/v1/assistant/drafting-sessions). Job đã bắt đầu trước đó vẫn chạy đến hết,
            và các endpoint đọc, duyệt, hủy, dừng, xóa job vẫn hoạt động.
            """
    )
    @ApiResponse(responseCode = "410", description = "ASSISTANT-COURSE_JOBS_RETIRED: tính năng đã thay đổi.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
    public BaseResponse<JobView> create() {
        throw new BusinessException(AssistantResponseCode.COURSE_JOBS_RETIRED);
    }

    @GetMapping
    @Operation(summary = "Các job tạo khóa học của tôi", description = "Mới nhất trước, tối đa 50.")
    public BaseResponse<List<JobSummary>> list() {
        gate.requireAccess();
        return BaseResponse.ok(jobs.list());
    }

    @GetMapping("/active")
    @Operation(
        summary = "Các job đang chạy và job lỗi hoặc đã dừng gần đây",
        description = "Job đang chờ, đang chạy hoặc chờ duyệt, cùng job failed hoặc stopped trong 3 ngày qua; "
            + "mỗi job kèm courseId. Ứng dụng dùng danh sách này khi mở để không mất tiến độ và thông báo lỗi sau khi tải lại."
    )
    public BaseResponse<List<JobSummary>> active() {
        gate.requireAccess();
        return BaseResponse.ok(jobs.active());
    }

    @GetMapping("/{jobId}")
    @Operation(
        summary = "Chi tiết một job",
        description = "Trạng thái, bước hiện tại, tiến độ và các bước đã có. Job của người khác trả not found."
    )
    public BaseResponse<JobView> get(@PathVariable @Positive Long jobId) {
        gate.requireAccess();
        return BaseResponse.ok(jobs.get(jobId));
    }

    @PostMapping("/{jobId}/cancel")
    @Operation(
        summary = "Hủy job",
        description = "Dừng job chưa kết thúc; kết quả đã tạo vẫn xem được. Job đã kết thúc trả lỗi validation JOB_FINISHED."
    )
    public BaseResponse<JobView> cancel(@PathVariable @Positive Long jobId) {
        gate.requireAccess();
        return BaseResponse.ok(jobs.cancel(jobId));
    }

    @PostMapping("/{jobId}/stop")
    @Operation(
        summary = "Dừng job",
        description = "Dừng job chưa kết thúc (status stopped). Các bài đã viết được giữ; bài đang viết và chưa viết "
            + "để trống ở trạng thái pending cho người dùng tự điền. Job đã kết thúc trả lỗi validation JOB_FINISHED."
    )
    public BaseResponse<JobView> stop(@PathVariable @Positive Long jobId) {
        gate.requireAccess();
        return BaseResponse.ok(jobs.stop(jobId));
    }

    @DeleteMapping("/{jobId}")
    @Operation(
        summary = "Xóa job",
        description = "Xóa job, các bước và tài liệu nguồn của nó. Job đang chạy phải hủy trước (JOB_RUNNING)."
    )
    public BaseResponse<Void> delete(@PathVariable @Positive Long jobId) {
        gate.requireAccess();
        jobs.delete(jobId);
        return BaseResponse.ok();
    }
}
