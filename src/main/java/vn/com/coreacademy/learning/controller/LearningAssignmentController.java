package vn.com.coreacademy.learning.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.service.LearningAssignmentService;

@RestController
@RequestMapping(value = "/api/v1/learning-assignments", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningAssignmentController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningAssignmentController {
    private final LearningAssignmentService assignments;

    @GetMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách đợt giao của đơn vị",
        description = """
            Đọc các đợt giao do assigningOrgId tạo, qua mọi khóa hoặc lọc một khóa, kèm số liệu người nhận.
            - Chỉ cần quyền quản lý đơn vị giao; không kiểm tra lại quyền chia sẻ khóa.
            - people/completed/overdue chỉ đếm người nhận còn hiệu lực; progressPercent = completed * 100 / people.
            - sort nhận id, startAt, dueAt hoặc title, kèm asc/desc.
            """
    )
    public BaseResponse<PageResponse<LearningDetails.AssignmentSummary>> list(
        @RequestParam @Positive Long assigningOrgId, @RequestParam(required = false) @Positive Long courseId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "id,desc") String sort) {
        return BaseResponse.ok(assignments.listForOrg(assigningOrgId, courseId, page, size, sort));
    }

    @PutMapping("/{assignmentId}/schedule")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Đổi lịch đợt giao",
        description = """
            Thay ngày mở học, hạn hoàn thành và nhắc hạn của đợt giao còn hiệu lực.
            - Áp dụng ngay cho mọi người nhận còn hiệu lực; người đã thu hồi giữ nguyên.
            - Nhắc đang chờ bị hủy và lập lại theo hạn mới; đợt đã thu hồi trả LEARNING-CONFLICT.
            """
    )
    public BaseResponse<LearningDetails.AssignmentDetail> reschedule(@PathVariable @Positive Long assignmentId,
        @Valid @RequestBody LearningRequests.AssignmentScheduleRequest request) {
        return BaseResponse.ok(assignments.reschedule(assignmentId, request));
    }

    @PostMapping("/{assignmentId}/remind")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Nhắc học ngay",
        description = """
            Tạo nhắc học trong ứng dụng cho người nhận còn hiệu lực chưa hoàn thành; trả số người được nhắc.
            - Mỗi người được nhắc thủ công tối đa một lần mỗi ngày (Asia/Ho_Chi_Minh) cho một đợt.
            - Đợt không có hạn hoàn thành trả lỗi validation trên dueAt.
            """
    )
    public BaseResponse<LearningDetails.RemindResult> remind(@PathVariable @Positive Long assignmentId) {
        return BaseResponse.ok(assignments.remind(assignmentId));
    }

    @PostMapping("/{assignmentId}/revoke")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Thu hồi cả đợt giao",
        description = """
            Thu hồi mọi người nhận còn hiệu lực với cùng lý do và chuyển đợt sang revoked.
            - Không xóa enrollment/điểm/tiến độ; giữ quyền học nếu còn nguồn hợp lệ khác.
            """
    )
    public BaseResponse<LearningDetails.AssignmentDetail> revokeAssignment(@PathVariable @Positive Long assignmentId,
        @Valid @RequestBody LearningRequests.RevokeRequest request) {
        return BaseResponse.ok(assignments.revokeAssignment(assignmentId, request));
    }

    @GetMapping("/{assignmentId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xem đợt giao và người nhận",
        description = """
            Chỉ quản lý đơn vị giao được xem danh sách người nhận và các nguồn target.
            """
    )
    public BaseResponse<LearningDetails.AssignmentDetail> get(@PathVariable @Positive Long assignmentId) {
        return BaseResponse.ok(assignments.get(assignmentId));
    }

    @PostMapping("/recipients/{recipientId}/revoke")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Thu hồi một nghĩa vụ giao học",
        description = """
            Không xóa enrollment/điểm/tiến độ; giữ quyền học nếu còn nguồn hợp lệ khác.
            """
    )
    public BaseResponse<LearningViews.AssignmentRecipientView> revoke(@PathVariable @Positive Long recipientId,
        @Valid @RequestBody LearningRequests.RevokeRequest request) {
        return BaseResponse.ok(assignments.revoke(recipientId, request));
    }

}
