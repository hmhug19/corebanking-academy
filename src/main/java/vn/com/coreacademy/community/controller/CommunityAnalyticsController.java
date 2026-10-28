package vn.com.coreacademy.community.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.AnalyticsDtos.ActivityPoint;
import vn.com.coreacademy.community.dto.AnalyticsDtos.AnalyticsSummary;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.service.CommunityAnalyticsService;
import vn.com.coreacademy.community.service.CommunityDocumentService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community analytics")
public class CommunityAnalyticsController {
    private final CommunityAnalyticsService analytics;
    private final CommunityDocumentService documents;

    @GetMapping("/analytics/overview")
    @Operation(summary = "Thống kê chia sẻ kiến thức")
    @ApiResponse(responseCode = "200", description = "Chỉ tính nguồn caller còn quyền đọc.", useReturnTypeSchema = true)
    public BaseResponse<AnalyticsSummary> overview(@RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to, @RequestParam(required = false) Long guildId) {
        return BaseResponse.ok(analytics.overview(from, to, guildId));
    }

    @GetMapping("/analytics/unanswered")
    @Operation(summary = "Thống kê câu hỏi chưa được trả lời")
    @ApiResponse(responseCode = "200", description = "Không tính draft answer.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<ContentCard>> unanswered(@RequestParam(defaultValue = "0") int olderThanDays,
        @RequestParam(required = false) Long guildId, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(analytics.unanswered(olderThanDays, guildId, page, size));
    }

    @GetMapping("/analytics/stale")
    @Operation(summary = "Thống kê nội dung cần cập nhật")
    @ApiResponse(responseCode = "200", description = "Nội dung lỗi thời hoặc đến hạn.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<ContentCard>> stale(@RequestParam(required = false) Long guildId,
        @RequestParam(required = false) String kind, @RequestParam(required = false) LocalDate dueBefore,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(documents.due(guildId, kind, dueBefore, page, size));
    }

    @GetMapping("/analytics/activity")
    @Operation(summary = "Thống kê hoạt động theo thời gian")
    @ApiResponse(responseCode = "200", description = "Bucket UTC, gồm kỳ không có hoạt động.",
        useReturnTypeSchema = true)
    public BaseResponse<List<ActivityPoint>> activity(@RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "day") String bucket,
        @RequestParam(required = false) Long guildId) {
        return BaseResponse.ok(analytics.activity(from, to, bucket, guildId));
    }

    @PostMapping("/contents/{contentId}/view")
    @Operation(summary = "Ghi nhận lượt đọc nội dung")
    @ApiResponse(responseCode = "200", description = "Một unique view mỗi user/content/ngày UTC.",
        useReturnTypeSchema = true)
    public BaseResponse<Void> view(@PathVariable @Positive Long contentId) {
        analytics.view(contentId);
        return BaseResponse.ok(null);
    }
}
