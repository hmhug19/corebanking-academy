package vn.com.coreacademy.assistant.course;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assistant.agent.AssistantGate;
import vn.com.coreacademy.assistant.course.CourseJobViews.ContentView;
import vn.com.coreacademy.assistant.course.CourseJobViews.DecisionRequest;
import vn.com.coreacademy.assistant.course.CourseJobViews.JobView;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/assistant/course-jobs/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant course generation")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CourseDraftController {
    private final AssistantGate gate;
    private final CourseDraftService drafts;

    @GetMapping("/content")
    @Operation(summary = "Nội dung AI đã tạo cho job", description = "Dàn ý, các bài học và câu hỏi đề xuất; chỉ chủ job.")
    public BaseResponse<ContentView> content(@PathVariable @Positive long jobId) {
        gate.requireAccess();
        return BaseResponse.ok(drafts.content(jobId));
    }

    @PutMapping("/outline")
    @Operation(summary = "Sửa dàn ý", description = "Chỉ trước khi duyệt; sau khi duyệt dàn ý bị khóa.")
    public BaseResponse<ContentView> updateOutline(@PathVariable @Positive long jobId, @RequestBody JsonNode outline) {
        gate.requireAccess();
        return BaseResponse.ok(drafts.updateOutline(jobId, outline));
    }

    @PostMapping("/outline/approve")
    @Operation(summary = "Duyệt dàn ý", description = "Khóa dàn ý và bắt đầu viết các bài học, rồi câu hỏi.")
    public BaseResponse<ContentView> approve(@PathVariable @Positive long jobId) {
        gate.requireAccess();
        return BaseResponse.ok(drafts.approveOutline(jobId));
    }

    @PostMapping("/lessons/{key}/regenerate")
    @Operation(summary = "Tạo lại một bài học", description = "Chỉ bài đó được viết lại; các bài khác giữ nguyên.")
    public BaseResponse<ContentView> regenerate(@PathVariable @Positive long jobId, @PathVariable String key) {
        gate.requireAccess();
        return BaseResponse.ok(drafts.regenerateLesson(jobId, key));
    }

    @PutMapping("/questions/{key}/{index}")
    @Operation(summary = "Chấp nhận hoặc loại câu hỏi đề xuất", description = "decision: accepted | rejected | pending.")
    public BaseResponse<ContentView> decide(@PathVariable @Positive long jobId, @PathVariable String key,
                                            @PathVariable int index, @RequestBody DecisionRequest request) {
        gate.requireAccess();
        return BaseResponse.ok(drafts.decide(jobId, key, index, request.decision()));
    }

    @PostMapping("/retry")
    @Operation(summary = "Chạy lại các bước lỗi")
    public BaseResponse<ContentView> retry(@PathVariable @Positive long jobId) {
        gate.requireAccess();
        return BaseResponse.ok(drafts.retry(jobId));
    }

    @PostMapping("/draft")
    @Operation(summary = "Tạo khóa học nháp",
        description = "Cần dàn ý đã duyệt và mọi bài học xong. Khóa học luôn ở trạng thái nháp, đánh dấu do AI tạo; "
            + "chỉ câu hỏi đã chấp nhận được ghi vào ngân hàng câu hỏi. Gọi lại trả cùng bản nháp.")
    public BaseResponse<JobView> draft(@PathVariable @Positive long jobId) {
        gate.requireAccess();
        return BaseResponse.ok(drafts.createDraft(jobId));
    }
}
