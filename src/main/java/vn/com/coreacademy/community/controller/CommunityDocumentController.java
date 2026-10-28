package vn.com.coreacademy.community.controller;

import java.time.LocalDate;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.ContentRequests.PublishRequest;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.RevisionDetail;
import vn.com.coreacademy.community.dto.DocumentDtos.ApprovalResult;
import vn.com.coreacademy.community.dto.DocumentDtos.ApprovalView;
import vn.com.coreacademy.community.dto.DocumentDtos.MaintenanceRequest;
import vn.com.coreacademy.community.dto.DocumentDtos.MaintenanceReviewView;
import vn.com.coreacademy.community.dto.DocumentDtos.RadarCard;
import vn.com.coreacademy.community.dto.DocumentDtos.ReviewSchedule;
import vn.com.coreacademy.community.dto.DocumentDtos.RevisionCard;
import vn.com.coreacademy.community.dto.DocumentDtos.RevisionDecision;
import vn.com.coreacademy.community.dto.DocumentDtos.WikiLocation;
import vn.com.coreacademy.community.dto.DocumentDtos.WikiPageCard;
import vn.com.coreacademy.community.dto.DocumentDtos.WikiPageView;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateRadar;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateWiki;
import vn.com.coreacademy.community.service.CommunityDocumentService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Community Wiki and Radar")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CommunityDocumentController {
    private final CommunityDocumentService documents;

    @GetMapping("/guilds/{guildId}/wiki/pages")
    @ApiResponse(responseCode = "200", description = "Trang Wiki từng cấp còn ACL.", useReturnTypeSchema = true)
    @Operation(summary = "Danh sách trang Wiki")
    public BaseResponse<PageResponse<WikiPageCard>> wiki(@PathVariable @Positive Long guildId,
        @RequestParam(required = false) Long parentPageId, @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(documents.wiki(guildId, parentPageId, q, page, size));
    }

    @PostMapping("/guilds/{guildId}/wiki/pages")
    @ApiResponse(responseCode = "200", description = "Wiki draft và vị trí trong Guild.", useReturnTypeSchema = true)
    @Operation(summary = "Tạo trang Wiki")
    public BaseResponse<WikiPageView> createWiki(@PathVariable @Positive Long guildId,
        @Valid @RequestBody CreateWiki request) {
        return BaseResponse.ok(documents.createWiki(guildId, request));
    }

    @PutMapping("/wiki/pages/{contentId}/location")
    @ApiResponse(responseCode = "200", description = "Vị trí mới, chặn cycle/cross-Guild.", useReturnTypeSchema = true)
    @Operation(summary = "Di chuyển trang Wiki")
    public BaseResponse<WikiPageView> location(@PathVariable @Positive Long contentId,
        @Valid @RequestBody WikiLocation request) {
        return BaseResponse.ok(documents.location(contentId, request));
    }

    @PostMapping("/contents/{contentId}/submit")
    @ApiResponse(responseCode = "200", description = "Revision submitted bất biến.", useReturnTypeSchema = true)
    @Operation(summary = "Gửi duyệt Wiki hoặc Radar")
    public BaseResponse<RevisionDetail> submit(@PathVariable @Positive Long contentId,
        @Valid @RequestBody PublishRequest request) {
        return BaseResponse.ok(documents.submit(contentId, request));
    }

    @PostMapping("/revisions/{revisionId}/decisions")
    @ApiResponse(responseCode = "200", description = "Quyết định đúng revision, không tự duyệt.",
        useReturnTypeSchema = true)
    @Operation(summary = "Phê duyệt hoặc yêu cầu sửa revision")
    public BaseResponse<ApprovalResult> decision(@PathVariable @Positive Long revisionId,
        @Valid @RequestBody RevisionDecision request) {
        return BaseResponse.ok(documents.decide(revisionId, request));
    }

    @GetMapping("/revisions/{revisionId}/approvals")
    @ApiResponse(responseCode = "200", description = "Lịch sử quyết định còn quyền revision.",
        useReturnTypeSchema = true)
    @Operation(summary = "Xem quyết định phê duyệt")
    public BaseResponse<PageResponse<ApprovalView>> approvals(@PathVariable @Positive Long revisionId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(documents.approvals(revisionId, page, size));
    }

    @PostMapping("/contents/{contentId}/maintenance-reviews")
    @ApiResponse(responseCode = "200", description = "Rà soát độ mới đúng current revision.",
        useReturnTypeSchema = true)
    @Operation(summary = "Ghi nhận rà soát nội dung")
    public BaseResponse<MaintenanceReviewView> maintain(@PathVariable @Positive Long contentId,
        @Valid @RequestBody MaintenanceRequest request) {
        return BaseResponse.ok(documents.maintain(contentId, request));
    }

    @GetMapping("/maintenance/review-due")
    @ApiResponse(responseCode = "200", description = "Nội dung đến hạn/lỗi thời theo ACL.", useReturnTypeSchema = true)
    @Operation(summary = "Danh sách cần rà soát")
    public BaseResponse<PageResponse<ContentCard>> due(@RequestParam(required = false) Long guildId,
        @RequestParam(required = false) String kind, @RequestParam(required = false) LocalDate dueBefore,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(documents.due(guildId, kind, dueBefore, page, size));
    }

    @GetMapping("/guilds/{guildId}/radar")
    @ApiResponse(responseCode = "200", description = "Radar từ cùng revision đã duyệt.", useReturnTypeSchema = true)
    @Operation(summary = "Xem Tech Radar")
    public BaseResponse<PageResponse<RadarCard>> radar(@PathVariable @Positive Long guildId,
        @RequestParam(required = false) String stage, @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(documents.radar(guildId, stage, q, page, size));
    }

    @PostMapping("/guilds/{guildId}/radar")
    @ApiResponse(responseCode = "200", description = "Radar draft, stage chỉ đổi qua revision mới.",
        useReturnTypeSchema = true)
    @Operation(summary = "Đề xuất mục Tech Radar")
    public BaseResponse<ContentDetail> createRadar(@PathVariable @Positive Long guildId,
        @Valid @RequestBody CreateRadar request) {
        return BaseResponse.ok(documents.createRadar(guildId, request));
    }

    @GetMapping("/contents/{contentId}/maintenance-reviews")
    @ApiResponse(responseCode = "200", description = "Các lần rà soát còn quyền đọc nguồn.", useReturnTypeSchema = true)
    @Operation(summary = "Lịch sử rà soát độ mới")
    public BaseResponse<PageResponse<MaintenanceReviewView>> maintenance(@PathVariable @Positive Long contentId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(documents.maintenance(contentId, page, size));
    }

    @PutMapping("/wiki/pages/{contentId}/review-schedule")
    @ApiResponse(responseCode = "200", description = "Lịch vận hành mới, không đổi metadata đã duyệt.",
        useReturnTypeSchema = true)
    @Operation(summary = "Cập nhật lịch rà soát Wiki")
    public BaseResponse<WikiPageView> schedule(@PathVariable @Positive Long contentId,
        @Valid @RequestBody ReviewSchedule request) {
        return BaseResponse.ok(documents.schedule(contentId, request));
    }

    @GetMapping("/moderation/revision-queue")
    @ApiResponse(responseCode = "200", description = "Chỉ submitted Wiki/Radar trong org approver.",
        useReturnTypeSchema = true)
    @Operation(summary = "Hàng đợi revision chờ duyệt")
    public BaseResponse<PageResponse<RevisionCard>> queue(@RequestParam(required = false) String kind,
        @RequestParam(required = false) Long guildId, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(documents.queue(kind, guildId, page, size));
    }
}
