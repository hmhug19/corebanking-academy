package vn.com.coreacademy.learning.controller;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.constants.LearningCodes.LeaderboardPeriod;
import vn.com.coreacademy.learning.constants.LearningCodes.LeaderboardScope;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningBadgeViews;
import vn.com.coreacademy.learning.dto.LearningLevelViews;
import vn.com.coreacademy.learning.dto.LearningPointsViews;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningStreakViews;
import vn.com.coreacademy.learning.service.BadgeService;
import vn.com.coreacademy.learning.service.LearnerPreferenceService;
import vn.com.coreacademy.learning.service.LearningLeaderboardService;
import vn.com.coreacademy.learning.service.LearningUnitLeaderboardService;
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
    private final BadgeService badges;
    private final LearningUnitLeaderboardService units;
    private final LearnerPreferenceService preferences;

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

    @GetMapping("/badges")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Huy hiệu của tôi",
        description = """
            Huy hiệu đã nhận (mới nhất trước) và huy hiệu chưa nhận cùng điều kiện nhận.
            - Huy hiệu chương trình chưa nhận có progress {done, total} (số khóa x/y); loại khác có progress null.
            - Huy hiệu đã ngừng dùng chỉ hiện với người đã nhận (retired = true) và không bao giờ bị thu hồi.
            - Lần đọc đầu tiên của hệ thống tạo bộ huy hiệu khởi đầu và trao âm thầm cho người đã đủ điều kiện.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            """
    )
    public BaseResponse<LearningBadgeViews.MyBadges> badges() {
        return BaseResponse.ok(badges.mine());
    }

    @GetMapping("/preferences")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tùy chọn của tôi",
        description = """
            Tùy chọn gamification của chính người gọi: showOnLeaderboard (mặc định true).
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403. Không có cách đọc tùy chọn của người khác.
            """
    )
    public BaseResponse<LearningPointsViews.LearnerPreferences> preferences() {
        return BaseResponse.ok(preferences.mine());
    }

    @PutMapping("/preferences")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Đổi tùy chọn của tôi",
        description = """
            Đặt showOnLeaderboard. Khi false:
            - người khác không còn thấy tên trên bảng xếp hạng cá nhân và hạng của họ khép lại, không có dòng "Ẩn danh";
            - chính học viên vẫn thấy hạng mình sẽ có nếu hiện;
            - XP của họ vẫn tính vào đơn vị trên bảng đơn vị.
            - Chỉ sửa được tùy chọn của chính người gọi; học viên đã ẩn hiện lại khi đặt true.
            """
    )
    public BaseResponse<LearningPointsViews.LearnerPreferences> updatePreferences(
        @Valid @RequestBody LearningRequests.LearnerPreferencesRequest request) {
        return BaseResponse.ok(preferences.update(request.showOnLeaderboard()));
    }

    @GetMapping("/leaderboard/units")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Bảng xếp hạng đơn vị",
        description = """
            Xếp hạng đơn vị theo XP trung bình mỗi học viên đang hoạt động trong kỳ, kèm tỷ lệ tham gia.
            - period: week, month (mặc định) hoặc all theo giờ Asia/Ho_Chi_Minh; offset lùi về tuần hoặc tháng trước.
            - Chỉ đơn vị từ 5 học viên trở lên được xếp hạng. Trả top 10 và đơn vị của người gọi (myUnit); khi đơn vị của
              người gọi chưa đủ 5 học viên, myUnit là null và myUnitTooSmall là true. Không bao giờ trả các hạng cuối.
            - Học viên tắt hiển thị tên trên bảng vẫn được tính vào đơn vị.
            - Mọi tài khoản đã đăng nhập có thể xem. Đối tượng được xếp hạng vẫn là học viên đủ điều kiện.
            """
    )
    public BaseResponse<LearningPointsViews.UnitLeaderboard> unitLeaderboard(
        @Parameter(description = "week | month | all")
        @RequestParam(defaultValue = LeaderboardPeriod.MONTH) @Pattern(regexp = LeaderboardPeriod.PATTERN)
        String period,
        @RequestParam(defaultValue = "0") @Min(0) @Max(LearningLimits.MAX_LEADERBOARD_OFFSET) int offset) {
        return BaseResponse.ok(units.units(period, offset));
    }

    @GetMapping("/leaderboard")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Bảng xếp hạng điểm học tập",
        description = """
            Xếp hạng học viên đủ điều kiện học theo XP của kỳ, giảm dần; cùng XP chung hạng (1, 1, 3).
            - scope: team (nhóm của caller), org (đơn vị của caller, mặc định) hoặc all (mọi học viên trong hệ thống).
            - period: week (thứ Hai-Chủ nhật), month (mặc định) hoặc all, theo giờ Asia/Ho_Chi_Minh; offset lùi về
              tuần hoặc tháng trước (0 là kỳ hiện tại, bị bỏ qua khi period=all).
            - Trả top 10 và me (luôn có; rank null nếu caller không thuộc phạm vi). Khi me ngoài top 10, above là 1-2
              người ngay trên me và gapToAbove là XP còn thiếu để vượt người gần nhất có XP cao hơn. Không phân trang
              và không có tổng số người.
            - Mọi tài khoản đã đăng nhập có thể xem. Đối tượng được xếp hạng vẫn là học viên đủ điều kiện.
            """
    )
    public BaseResponse<LearningPointsViews.Leaderboard> leaderboard(
        @Parameter(description = "team | org | all")
        @RequestParam(defaultValue = LeaderboardScope.ORG) @Pattern(regexp = LeaderboardScope.PATTERN) String scope,
        @Parameter(description = "week | month | all")
        @RequestParam(defaultValue = LeaderboardPeriod.MONTH) @Pattern(regexp = LeaderboardPeriod.PATTERN)
        String period,
        @RequestParam(defaultValue = "0") @Min(0) @Max(LearningLimits.MAX_LEADERBOARD_OFFSET) int offset) {
        return BaseResponse.ok(leaderboard.leaderboard(scope, period, offset));
    }
}
