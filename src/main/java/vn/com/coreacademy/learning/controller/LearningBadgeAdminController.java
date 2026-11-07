package vn.com.coreacademy.learning.controller;

import java.util.List;

import jakarta.validation.Valid;

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
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.BadgeAdminDtos.AdminBadgeView;
import vn.com.coreacademy.learning.dto.BadgeAdminDtos.SaveBadge;
import vn.com.coreacademy.learning.service.BadgeAdminService;

@RestController
@RequestMapping(value = "/api/v1/admin/learning/badges", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningBadgeAdminController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningBadgeAdminController {
    private final BadgeAdminService badges;

    @GetMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách huy hiệu",
        description = """
            Mọi huy hiệu, kể cả đã ngừng dùng, kèm số người nhận, trạng thái khóa luật và tiến độ trao bù nền. Chỉ
            quản trị hệ thống (ADMIN hoặc SUPER_ADMIN, phạm vi GLOBAL); vai trò khác nhận 403.
            """
    )
    public BaseResponse<List<AdminBadgeView>> list() {
        return BaseResponse.ok(badges.list());
    }

    @PostMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạo huy hiệu",
        description = """
            Lưu và trả về ngay; việc trao bù cho người đã đủ điều kiện chạy ở nền, âm thầm (không thông báo), tiến độ
            nằm ở backfillStatus/backfillDone/backfillTotal của huy hiệu. Luật: COURSES_COMPLETED,
            FIRST_TRY_PERFECT, ASSIGNED_BEFORE_DEADLINE, STREAK_WORKING_DAYS cần paramN 1..1000; PROGRAM_COMPLETED
            cần paramProgramId. Ghi audit BADGE_CREATED. Chỉ quản trị hệ thống.
            """
    )
    public BaseResponse<AdminBadgeView> create(@Valid @RequestBody SaveBadge request) {
        return BaseResponse.ok(badges.create(request));
    }

    @PutMapping("/{badgeId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Sửa huy hiệu",
        description = """
            Sửa tên, mô tả, biểu tượng và luật. Khi đã có người nhận (locked), đổi luật bị từ chối với
            CORE-VALIDATION_FAILED (LOCKED); tên, mô tả, biểu tượng vẫn sửa được. Đổi luật khi chưa ai nhận thì trao bù
            lại ở nền. Ghi audit BADGE_UPDATED. Chỉ quản trị hệ thống.
            """
    )
    public BaseResponse<AdminBadgeView> update(@PathVariable long badgeId, @Valid @RequestBody SaveBadge request) {
        return BaseResponse.ok(badges.update(badgeId, request));
    }

    @PostMapping("/{badgeId}/retire")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Ngừng dùng huy hiệu",
        description = """
            Huy hiệu không còn được trao; người đã nhận giữ huy hiệu. Gọi lại là không đổi. Ghi audit BADGE_RETIRED.
            Chỉ quản trị hệ thống.
            """
    )
    public BaseResponse<AdminBadgeView> retire(@PathVariable long badgeId) {
        return BaseResponse.ok(badges.retire(badgeId));
    }
}
