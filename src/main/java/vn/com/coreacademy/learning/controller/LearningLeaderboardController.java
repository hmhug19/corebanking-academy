package vn.com.coreacademy.learning.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.LearningPointsViews;
import vn.com.coreacademy.learning.service.LearningPointsService;

@RestController
@RequestMapping(value = "/api/v1/me/learning", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningLeaderboardController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningLeaderboardController {
    private final LearningPointsService points;

    @GetMapping("/points")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Điểm học tập của tôi",
        description = """
            Điểm tính khi đọc từ bài học hoàn thành, bài kiểm tra đạt, khóa hoàn thành và chuỗi ngày học.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            - Không có hạng: hạng phụ thuộc phạm vi và nằm ở bảng xếp hạng.
            """
    )
    public BaseResponse<LearningPointsViews.MyPoints> points() {
        return BaseResponse.ok(points.mine());
    }
}
