package vn.com.coreacademy.learning.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.dto.AssessmentDashboard;
import vn.com.coreacademy.assessment.service.AssessmentDashboardService;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.LearningAnalytics;
import vn.com.coreacademy.learning.dto.LearningDashboard;
import vn.com.coreacademy.learning.service.LearningAnalyticsService;
import vn.com.coreacademy.learning.service.LearningDashboardService;
import vn.com.coreacademy.learning.service.LearningWeeklyActivityService;

@RestController
@RequestMapping("/api/v1/me/learning/dashboard")
@Tag(name = "Trang chủ học tập")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningDashboardController {
    private final LearningDashboardService dashboard;
    private final LearningAnalyticsService learning;
    private final AssessmentDashboardService assessment;
    private final LearningWeeklyActivityService weeklyActivity;

    @GetMapping
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(summary = "Tổng quan trang chủ học viên", description = """
        Trả KPI học tập, vị trí tiếp tục học, tối đa 5 hạn gần nhất và kết quả kiểm tra của chính người đăng nhập.
        - Tỷ lệ hoàn thành và tiến độ trung bình là hai chỉ số riêng; không đếm trùng nhiều đợt giao cùng lần học.
        - Điểm gần nhất/cao nhất chỉ tính bài thi cuối khóa; chưa có kết quả trả null.
        """)
    public BaseResponse<LearningDashboard> home() {
        return BaseResponse.ok(dashboard.home());
    }

    @GetMapping("/competencies")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(summary = "Biểu đồ năng lực của tôi", description = """
        Tổng hợp nhóm kiến thức từ một lượt đã chấm của mỗi bài thi cuối khóa; method=BEST hoặc LAST.
        - Mặc định BEST; tỷ lệ tính bằng tổng điểm đạt chia tổng điểm tối đa, không lấy trung bình phần trăm.
        - Chỉ trả nhóm đã được đánh giá; nhóm vắng mặt là chưa có dữ liệu, khác điểm 0.
        """)
    public BaseResponse<PageResponse<AssessmentDashboard.Competency>> competencies(
        @RequestParam(defaultValue = "BEST") String method, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(assessment.competencies(method, page, size));
    }

    @GetMapping("/activity/weekly")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(summary = "Hoạt động học theo tuần", description = "Số ngày có hoạt động học của người đăng nhập, theo tuần từ thứ Hai và múi giờ Việt Nam.")
    public BaseResponse<LearningWeeklyActivityService.WeeklyActivity> weeklyActivity(
        @RequestParam(defaultValue = "8") @Min(1) @Max(52) int weeks) {
        return BaseResponse.ok(weeklyActivity.get(weeks));
    }

    @GetMapping("/programs")
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(summary = "Tiến độ theo chương trình của tôi", description = """
        Nhóm các lần học hiện hành theo chương trình; trả số khóa hoàn thành và tiến độ trung bình riêng.
        - Phân trang tại database; đây là tiến độ chương trình, chưa áp dụng điều kiện tiên quyết giữa các khóa.
        """)
    public BaseResponse<PageResponse<LearningAnalytics.Group>> programs(@RequestParam(defaultValue = "0") int page,
                                                                        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(learning.myPrograms(page, size));
    }
}
