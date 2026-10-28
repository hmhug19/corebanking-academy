package vn.com.coreacademy.community.controller;

import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.ModerationRequests.AssignReport;
import vn.com.coreacademy.community.dto.ModerationRequests.CreateReport;
import vn.com.coreacademy.community.dto.ModerationRequests.ModerationActionRequest;
import vn.com.coreacademy.community.dto.ModerationViews.EvidenceView;
import vn.com.coreacademy.community.dto.ModerationViews.ReportCard;
import vn.com.coreacademy.community.dto.ModerationViews.ReportView;
import vn.com.coreacademy.community.service.CommunityReportService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Community reports")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CommunityReportController {
    private final CommunityReportService reports;

    @PostMapping("/reports")
    @ApiResponse(responseCode = "200", description = "Report mới hoặc report đang mở cùng grain.",
        useReturnTypeSchema = true)
    @Operation(summary = "Báo cáo nội dung")
    public BaseResponse<ReportView> create(@Valid @RequestBody CreateReport request) {
        return BaseResponse.ok(reports.create(request));
    }

    @GetMapping("/moderation/reports")
    @ApiResponse(responseCode = "200", description = "Metadata tối thiểu, không có excerpt riêng tư.",
        useReturnTypeSchema = true)
    @Operation(summary = "Hàng đợi report trong đơn vị")
    public BaseResponse<PageResponse<ReportCard>> list(@RequestParam(required = false) String status,
        @RequestParam(defaultValue = "false") boolean assignedToMe, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(reports.list(status, assignedToMe, page, size));
    }

    @PostMapping("/moderation/reports/{reportId}/assign")
    @ApiResponse(responseCode = "200", description = "Report được giao xử lý.", useReturnTypeSchema = true)
    @Operation(summary = "Giao case cho moderator")
    public BaseResponse<ReportView> assign(@PathVariable @Positive Long reportId,
        @Valid @RequestBody AssignReport request) {
        return BaseResponse.ok(reports.assign(reportId, request));
    }

    @GetMapping("/moderation/reports/{reportId}/evidence")
    @ApiResponse(responseCode = "200", description = "Evidence chỉ cho moderator được giao, có audit.",
        useReturnTypeSchema = true)
    @Operation(summary = "Mở evidence của case")
    public BaseResponse<EvidenceView> evidence(@PathVariable @Positive Long reportId) {
        return BaseResponse.ok(reports.evidence(reportId));
    }

    @PostMapping("/moderation/reports/{reportId}/actions")
    @ApiResponse(responseCode = "200", description = "Quyết định và audit append-only.", useReturnTypeSchema = true)
    @Operation(summary = "Xử lý report theo version của case và target")
    public BaseResponse<ReportView> action(@PathVariable @Positive Long reportId,
        @Valid @RequestBody ModerationActionRequest request) {
        return BaseResponse.ok(reports.action(reportId, request));
    }
}
