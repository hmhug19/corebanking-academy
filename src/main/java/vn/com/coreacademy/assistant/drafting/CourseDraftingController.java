package vn.com.coreacademy.assistant.drafting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.assistant.agent.AssistantGate;
import vn.com.coreacademy.assistant.drafting.DraftingViews.SessionView;
import vn.com.coreacademy.assistant.drafting.DraftingViews.StartFields;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/assistant/drafting-sessions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant course drafting")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CourseDraftingController {
    private final AssistantGate gate;
    private final CourseDraftingService sessions;
    private final ObjectMapper json;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "Bắt đầu Soạn cùng trợ lý từ một tài liệu",
        description = """
            multipart: file (.pdf, .docx hoặc .md, tối đa theo app.knowledge.max-file-size), fields (JSON StartFields, tùy chọn) và conversationId (tùy chọn, hội thoại của chính người gọi).
            - Chỉ vai trò quản lý khóa học (INSTRUCTOR, ADMIN, SUPER_ADMIN) có chức năng AI_ASSISTANT; người khác nhận 403.
            - Đơn vị sở hữu: giảng viên luôn là đơn vị của mình (gửi đơn vị khác là 403); ADMIN/SUPER_ADMIN chọn ownerOrgId trong phạm vi quản lý, bỏ trống thì là đơn vị của mình.
            - Tệp trở thành tài liệu course_source của người gọi: chỉ người đó đọc được, không bao giờ được liệt kê hay tìm thấy qua trợ lý.
            - Tính chung giới hạn app.assistant.generation.jobs-per-day trong 24 giờ với việc tạo khóa học bằng AI; vượt giới hạn trả ASSISTANT-JOB_RATE_LIMITED kèm thời gian chờ.
            - Trả về phiên với bước 1 ở trạng thái proposing; trợ lý đọc tài liệu và đề xuất bước 1 ở nền. Tài liệu không đọc được hoặc quá ngắn làm phiên failed với error đọc được.
            """
    )
    public BaseResponse<SessionView> start(@RequestPart MultipartFile file,
                                           @RequestPart(value = "fields", required = false) String fields,
                                           @RequestParam(value = "conversationId", required = false)
                                           @Positive Long conversationId) {
        gate.requireAccess();
        return BaseResponse.ok(sessions.start(file, parse(fields), conversationId));
    }

    /** The part is JSON text, whether the client labelled it application/json or sent a plain form field. */
    private StartFields parse(String text) {
        if (text == null || text.isBlank()) {
            return StartFields.NONE;
        }
        try {
            return json.readValue(text, StartFields.class);
        } catch (JsonProcessingException exception) {
            throw BusinessException.validation("fields", "INVALID_JSON", "Thông tin gửi kèm không hợp lệ.");
        }
    }

    @GetMapping("/{sessionId}")
    @Operation(
        summary = "Chi tiết một phiên Soạn cùng trợ lý",
        description = "Trạng thái, bước hiện tại, tài liệu nguồn và các bước đã tới cùng đề xuất của từng bước. "
            + "Phiên của người khác trả not found."
    )
    public BaseResponse<SessionView> get(@PathVariable @Positive Long sessionId) {
        gate.requireAccess();
        return BaseResponse.ok(sessions.get(sessionId));
    }

    @GetMapping("/course/{courseId}")
    @Operation(
        summary = "Phiên Soạn cùng trợ lý của một khóa học",
        description = "Phiên mới nhất của người gọi đã tạo bản nháp khóa học này, để trang sửa khóa học mở đúng bước "
            + "hiện tại. Không có phiên (hoặc phiên của người khác) trả data null."
    )
    public BaseResponse<SessionView> forCourse(@PathVariable @Positive Long courseId) {
        gate.requireAccess();
        return BaseResponse.ok(sessions.forCourse(courseId));
    }

    @PostMapping(value = "/{sessionId}/steps/{step}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Chốt một bước với giá trị hiện có trên trang",
        description = """
            Thân theo bước; bước 1: { course: CreateCourse }.
            - Chỉ chốt được bước hiện tại khi đã đề xuất xong (proposed), lỗi (failed) hoặc tự điền (manual); khác đi trả             CORE-VALIDATION_FAILED trường step mã INVALID_STATE.
            - Bước 1 tạo bản nháp khóa học qua đường tạo khóa học thông thường, gắn vào phiên rồi chuyển sang bước 2             (proposing). Chốt lại bước 1 khi đã có khóa học trả phiên như hiện tại, không tạo khóa thứ hai.
            - Mã khóa học đã có trả LEARNING-DUPLICATE trường course.code; phiên đề xuất mã mới ở bước 1.
            - Bước 4 ({}) chỉ kết thúc phiên (status finished): không giao, không chia sẻ, không xuất bản gì.
            """
    )
    public BaseResponse<SessionView> confirm(@PathVariable @Positive Long sessionId,
                                             @PathVariable @Min(1) @Max(4) Integer step,
                                             @RequestBody JsonNode body) {
        gate.requireAccess();
        return BaseResponse.ok(sessions.confirm(sessionId, step, body));
    }
}
