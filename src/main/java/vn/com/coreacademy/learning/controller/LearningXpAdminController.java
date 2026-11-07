package vn.com.coreacademy.learning.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.XpAdjustmentDtos.AdjustXp;
import vn.com.coreacademy.learning.dto.XpAdjustmentDtos.XpAdjustmentResult;
import vn.com.coreacademy.learning.service.XpAdjustmentService;

@RestController
@RequestMapping(value = "/api/v1/admin/learning", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningXpAdminController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningXpAdminController {
    private final XpAdjustmentService adjustments;

    @PostMapping("/xp-adjustments")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Điều chỉnh XP của học viên",
        description = """
            Ghi một dòng bù trừ ADJUSTMENT vào sổ XP và một mục audit XP_ADJUSTED (ai, khi nào, cho ai, số XP, lý do).
            - Chỉ quản trị hệ thống (ADMIN hoặc SUPER_ADMIN, phạm vi GLOBAL); vai trò khác nhận 403.
            - amount có dấu, khác 0, tối đa 100000 theo trị tuyệt đối, và không được làm tổng XP của học viên âm.
            - reason bắt buộc; thiếu lý do là CORE-VALIDATION_FAILED.
            - Không sửa hay xóa dòng nào có sẵn; học viên thấy phần "Điều chỉnh" trong XP của mình.
            """
    )
    public BaseResponse<XpAdjustmentResult> adjust(@Valid @RequestBody AdjustXp request) {
        return BaseResponse.ok(adjustments.adjust(request));
    }
}
