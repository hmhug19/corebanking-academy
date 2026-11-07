package vn.com.coreacademy.learning.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.constants.LearningCodes.LeaderboardScope;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningLevelViews;
import vn.com.coreacademy.learning.dto.LearningPointsViews;
import vn.com.coreacademy.learning.dto.LearningStreakViews;
import vn.com.coreacademy.learning.service.LearningLeaderboardService;
import vn.com.coreacademy.learning.service.LearningPointsService;
import vn.com.coreacademy.learning.service.LearningStreakService;
import vn.com.coreacademy.learning.service.LevelService;

@RestController
@RequestMapping(value = "/api/v1/me/learning", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningLeaderboardController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningLeaderboardController {
    private final LearningPointsService points;
    private final LearningLeaderboardService leaderboard;
    private final LearningStreakService streaks;
    private final LevelService levels;

    @GetMapping("/points")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "XP của tôi",
        description = """
            XP đọc từ sổ ghi XP, chia theo nguồn: bài học, bài kiểm tra đạt, thưởng đạt ngay lần đầu và khóa học.
            - Chuỗi ngày học không cộng XP; streakDays là chuỗi hiện tại theo ngày làm việc, chỉ để hiển thị.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            - Không có hạng: hạng phụ thuộc phạm vi và nằm ở bảng xếp hạng.
            """
    )
    public BaseResponse<LearningPointsViews.MyPoints> points() {
        return BaseResponse.ok(points.mine());
    }

    @GetMapping("/level")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cấp độ của tôi",
        description = """
            Cấp theo tổng XP và bậc thang đang áp dụng (mặc định 6 cấp, từ "Giao dịch viên tập sự" đến "Chuyên gia Core Banking").
            - level là cấp cao nhất từng đạt, không bao giờ giảm, kể cả khi quản trị nâng ngưỡng.
            - xpToNext và progressPercent là null ở cấp cao nhất.
            - Cấp độ không mở quyền hay thay đổi giao việc nào.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            """
    )
    public BaseResponse<LearningLevelViews.MyLevel> level() {
        return BaseResponse.ok(levels.mine());
    }

    @GetMapping("/streak")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Chuỗi ngày học và lịch nhiệt của tôi",
        description = """
            Chuỗi tính theo ngày làm việc và dữ liệu lịch nhiệt 26 tuần (Asia/Ho_Chi_Minh).
            - Ngày đủ điều kiện khi có ít nhất một bài học hoàn thành hoặc một bài kiểm tra đã nộp; mở bài hay mở trang
              không tính.
            - Cuối tuần và ngày nghỉ của lịch chung không tính vào chuỗi và không làm đứt chuỗi; ngày làm bù là ngày làm
              việc. Học vào ngày nghỉ vẫn có số bài ở lịch nhưng không thêm gì cho chuỗi.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            """
    )
    public BaseResponse<LearningStreakViews.MyStreak> streak() {
        return BaseResponse.ok(streaks.mine());
    }

    @GetMapping("/leaderboard")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Bảng xếp hạng điểm học tập",
        description = """
            Xếp hạng học viên đủ điều kiện học theo điểm giảm dần; cùng điểm chung hạng (1, 1, 3).
            - scope: team (nhóm của caller), org (đơn vị của caller, mặc định) hoặc all (mọi học viên trong hệ thống).
            - me là dòng của caller, luôn có kể cả khi không nằm trong trang; rank của me là null nếu caller không thuộc
              phạm vi (xem nhóm khi chưa có nhóm).
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            """
    )
    public BaseResponse<LearningPointsViews.LeaderboardPage> leaderboard(
        @Parameter(description = "team | org | all")
        @RequestParam(defaultValue = LeaderboardScope.ORG) @Pattern(regexp = LeaderboardScope.PATTERN) String scope,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(LearningLimits.MAX_LEADERBOARD_PAGE_SIZE) int size) {
        return BaseResponse.ok(leaderboard.leaderboard(scope, page, size));
    }
}
