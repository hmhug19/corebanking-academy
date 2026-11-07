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
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.CalendarDayView;
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.ConfigVersionView;
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.SaveCalendarYear;
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.SaveConfig;
import vn.com.coreacademy.learning.service.GamificationConfigService;

@RestController
@RequestMapping(value = "/api/v1/admin/learning", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningGamificationConfigController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningGamificationConfigController {
    private final GamificationConfigService config;

    @GetMapping("/gamification/config")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cấu hình XP đang áp dụng",
        description = """
            Phiên bản cấu hình lớn nhất: trọng số XP, ngưỡng thưởng đạt ngay lần đầu, trần XP cộng đồng mỗi ngày và
            bậc thang cấp độ. Chỉ quản trị hệ thống (ADMIN hoặc SUPER_ADMIN, phạm vi GLOBAL); vai trò khác nhận 403.
            """
    )
    public BaseResponse<ConfigVersionView> current() {
        return BaseResponse.ok(config.current());
    }

    @GetMapping("/gamification/config/versions")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Lịch sử phiên bản cấu hình",
        description = "Mọi phiên bản, mới nhất trước, kèm người lưu và lúc lưu. Chỉ quản trị hệ thống."
    )
    public BaseResponse<List<ConfigVersionView>> versions() {
        return BaseResponse.ok(config.versions());
    }

    @PostMapping("/gamification/config")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Lưu phiên bản cấu hình mới",
        description = """
            Thêm một phiên bản bất biến áp dụng ngay từ lúc lưu; XP đã ghi giữ nguyên số cũ. Ghi audit
            GAMIFICATION_CONFIG_SAVED. Chỉ quản trị hệ thống.
            - Bậc thang: bậc đầu 0 XP, các bậc tăng dần ngặt, tên không rỗng, tối đa 20 bậc. Cấp học viên đã đạt được
              giữ nguyên dù ngưỡng mới cao hơn. Vi phạm là CORE-VALIDATION_FAILED.
            """
    )
    public BaseResponse<ConfigVersionView> save(@Valid @RequestBody SaveConfig request) {
        return BaseResponse.ok(config.save(request));
    }

    @GetMapping("/working-calendar/{year}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Lịch ngày nghỉ và ngày làm bù của một năm",
        description = "Chỉ các ngày ngoại lệ (HOLIDAY, MAKEUP_WORKDAY), ngày tăng dần. Chỉ quản trị hệ thống."
    )
    public BaseResponse<List<CalendarDayView>> calendar(@PathVariable int year) {
        return BaseResponse.ok(config.calendar(year));
    }

    @PutMapping("/working-calendar/{year}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Lưu lịch ngày nghỉ và ngày làm bù của một năm",
        description = """
            Thay toàn bộ ngày ngoại lệ của năm. Mỗi ngày phải thuộc năm đó, loại HOLIDAY hoặc MAKEUP_WORKDAY, không
            trùng ngày. Chuỗi ngày học tính theo lịch mới từ lần đánh giá kế tiếp. Ghi audit WORKING_CALENDAR_SAVED.
            Chỉ quản trị hệ thống.
            """
    )
    public BaseResponse<List<CalendarDayView>> saveCalendar(@PathVariable int year,
                                                            @Valid @RequestBody SaveCalendarYear request) {
        return BaseResponse.ok(config.saveCalendar(year, request));
    }
}
