package vn.com.coreacademy.assistant.course;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.assistant.agent.AssistantGate;
import vn.com.coreacademy.assistant.course.CourseJobViews.Inputs;
import vn.com.coreacademy.assistant.course.CourseJobViews.JobSummary;
import vn.com.coreacademy.assistant.course.CourseJobViews.JobView;
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
    private final ObjectMapper json;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "Bắt đầu tạo khóa học bằng AI từ một tài liệu",
        description = """
            multipart: file (.pdf, .docx hoặc .md, tối đa theo app.knowledge.max-file-size) và inputs (JSON, xem schema Inputs).
            - Chỉ vai trò quản lý khóa học (INSTRUCTOR, ADMIN, SUPER_ADMIN); học viên và người xem báo cáo nhận 403.
            - Đơn vị sở hữu: giảng viên luôn là đơn vị của mình (gửi đơn vị khác là 403); ADMIN/SUPER_ADMIN bắt buộc gửi ownerOrgId trong phạm vi quản lý, ngoài phạm vi là 403.
            - Tệp trở thành tài liệu phạm vi personal của người gọi, liên kết với job; không ai khác tìm thấy qua trợ lý.
            - Mỗi người tối đa app.assistant.generation.jobs-per-day job trong 24 giờ; vượt giới hạn trả ASSISTANT-JOB_RATE_LIMITED kèm thời gian chờ.
            - Chỉ một job chạy cùng lúc trên toàn hệ thống; job tiếp theo ở trạng thái queued cho đến lượt.
            """
    )
    public BaseResponse<JobView> create(@RequestPart MultipartFile file, @RequestPart("inputs") String inputs) {
        gate.requireAccess();
        return BaseResponse.ok(jobs.create(file, parse(inputs)));
    }

    /** The part is JSON text, whether the client labelled it application/json or sent a plain form field. */
    private Inputs parse(String text) {
        try {
            return json.readValue(text, Inputs.class);
        } catch (JsonProcessingException exception) {
            throw BusinessException.validation("inputs", "INVALID_JSON", "Thông tin khởi tạo không hợp lệ.");
        }
    }

    @GetMapping
    @Operation(summary = "Các job tạo khóa học của tôi", description = "Mới nhất trước, tối đa 50.")
    public BaseResponse<List<JobSummary>> list() {
        gate.requireAccess();
        return BaseResponse.ok(jobs.list());
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
