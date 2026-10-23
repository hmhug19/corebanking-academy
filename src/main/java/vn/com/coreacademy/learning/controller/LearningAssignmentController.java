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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
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
