package vn.com.coreacademy.assessment.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.service.CompetencyService;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/competencies", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CompetencyController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CompetencyController {
    private final CompetencyService competencies;

    @GetMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách nhóm năng lực",
        description = """
            Danh mục dùng chung, trả dạng phẳng có parentId để FE dựng cây; không gồm bản ghi đã xóa mềm.
            """
    )
    public BaseResponse<List<AssessmentViews.CompetencyView>> list(
        @Parameter(description = "ACTIVE hoặc INACTIVE; bỏ trống để lấy tất cả.") @RequestParam(required = false)
        String status) {
        return BaseResponse.ok(competencies.list(status));
    }

    @PostMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạo nhóm năng lực",
        description = """
            Chỉ quản trị phạm vi toàn hệ thống.
            - Mã không trùng với nhóm chưa xóa; nhóm cha phải tồn tại và không tạo vòng lặp.
            """
    )
    public BaseResponse<AssessmentViews.CompetencyView> create(
        @Valid @RequestBody AssessmentRequests.CompetencyRequest request) {
        return BaseResponse.ok(competencies.save(null, null, request));
    }

    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cập nhật nhóm năng lực",
        description = """
            Chỉ quản trị phạm vi toàn hệ thống; gửi expectedVersion là rowVersion vừa đọc.
            - INACTIVE không gắn được vào câu hỏi mới; báo cáo cũ vẫn giữ nhãn.
            """
    )
    public BaseResponse<AssessmentViews.CompetencyView> update(@PathVariable @Positive Long id,
        @RequestParam @Min(0) Long expectedVersion, @Valid @RequestBody AssessmentRequests.CompetencyRequest request) {
        return BaseResponse.ok(competencies.save(id, expectedVersion, request));
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xóa nhóm năng lực",
        description = """
            Chỉ quản trị phạm vi toàn hệ thống; xóa mềm khi không còn câu hỏi hoặc nhóm con gắn vào.
            - Đang được dùng trả ASSESSMENT-COMPETENCY_IN_USE; khi đó chỉ chuyển INACTIVE.
            """
    )
    public BaseResponse<Void> delete(@PathVariable @Positive Long id, @RequestParam @Min(0) Long expectedVersion) {
        competencies.delete(id, expectedVersion);
        return BaseResponse.ok();
    }
}
