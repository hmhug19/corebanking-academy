package vn.com.coreacademy.learning.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.LearningAnalytics;
import vn.com.coreacademy.learning.dto.LearningReportFilter;
import vn.com.coreacademy.learning.service.LearningAnalyticsService;

@RestController
@RequestMapping("/api/v1/reports/learning")
@Tag(name = "Báo cáo đào tạo")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningReportController {
    private final LearningAnalyticsService reports;

    @GetMapping("/dashboard")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(summary = "Tổng quan cho giảng viên và quản trị theo phạm vi khóa học quản lý")
    public BaseResponse<LearningAnalytics.Dashboard> dashboard() {
        return BaseResponse.ok(reports.dashboard());
    }

    @GetMapping("/summary")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(summary = "Tổng quan tiến độ đào tạo", description = """
        Tổng hợp các lần học hiện hành trong phạm vi báo cáo của người đăng nhập.
        - Filter đơn vị gồm cây con; đơn vị/nhóm của bản ghi lấy theo thời điểm ghi danh.
        - from/to lọc ngày ghi danh, không phải ảnh số liệu quá khứ; to không bao gồm.
        """)
    public BaseResponse<LearningAnalytics.Summary> summary(@Valid @ModelAttribute LearningReportFilter filter) {
        return BaseResponse.ok(reports.summary(filter));
    }

    @GetMapping("/enrollments")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(summary = "Chi tiết tiến độ học viên theo khóa", description = """
        Mỗi dòng là lần học hiện hành của một học viên trên một khóa, kèm hạn và trạng thái quá hạn thực tế.
        - Lọc userId, courseId, programId, fieldId, orgId, teamId, status, overdue, q và khoảng ngày ghi danh.
        - Phân trang tại database; sort hỗ trợ id, progressPercent, dueAt, lastAccessAt, enrolledAt.
        """)
    public BaseResponse<PageResponse<LearningAnalytics.EnrollmentRow>> enrollments(
        @Valid @ModelAttribute LearningReportFilter filter, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "id") String sort) {
        return BaseResponse.ok(reports.enrollments(filter, page, size, sort));
    }

    @GetMapping("/groups")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(summary = "Tổng hợp đào tạo theo đối tượng", description = """
        groupBy=user, course, program, org hoặc team; cùng bộ lọc và phạm vi với báo cáo chi tiết.
        - Trả số lượt học, số học viên/khóa phân biệt, trạng thái, tiến độ trung bình và tỷ lệ hoàn thành.
        - Các nhóm được phân trang; không cộng số học viên giữa các nhóm để suy ra tổng người duy nhất.
        """)
    public BaseResponse<PageResponse<LearningAnalytics.Group>> groups(
        @Valid @ModelAttribute LearningReportFilter filter, @RequestParam(defaultValue = "course") String groupBy,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(reports.groups(filter, groupBy, page, size));
    }

    @GetMapping(value = "/enrollments/export", produces = {"text/csv", MediaType.APPLICATION_JSON_VALUE})
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(summary = "Xuất báo cáo tiến độ ra CSV", description = """
        Xuất cùng dữ liệu, bộ lọc và phạm vi với báo cáo chi tiết; tối đa 10.000 dòng.
        - UTF-8 BOM cho Excel, xử lý ký tự công thức; vượt giới hạn trả lỗi thay vì cắt dữ liệu.
        """)
    public ResponseEntity<byte[]> export(@Valid @ModelAttribute LearningReportFilter filter,
        @RequestParam(defaultValue = "id") String sort) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=learning-report.csv")
            .body(reports.export(filter, sort));
    }
}
