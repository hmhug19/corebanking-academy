package vn.com.coreacademy.community.controller;

import java.util.List;

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
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateReview;
import vn.com.coreacademy.community.dto.DocumentRequests.FollowUpReview;
import vn.com.coreacademy.community.dto.ReviewDtos.Close;
import vn.com.coreacademy.community.dto.ReviewDtos.Conclude;
import vn.com.coreacademy.community.dto.ReviewDtos.CreateThread;
import vn.com.coreacademy.community.dto.ReviewDtos.InvitationView;
import vn.com.coreacademy.community.dto.ReviewDtos.Invite;
import vn.com.coreacademy.community.dto.ReviewDtos.Respond;
import vn.com.coreacademy.community.dto.ReviewDtos.ReviewCard;
import vn.com.coreacademy.community.dto.ReviewDtos.ReviewView;
import vn.com.coreacademy.community.dto.ReviewDtos.ThreadAction;
import vn.com.coreacademy.community.dto.ReviewDtos.ThreadView;
import vn.com.coreacademy.community.service.CommunityReviewService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community architecture reviews")
public class CommunityReviewController {
    private final CommunityReviewService reviews;

    @GetMapping("/reviews")
    @Operation(summary = "Tìm đề nghị review kiến trúc")
    @ApiResponse(responseCode = "200", description = "Đề nghị còn quyền đọc.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<ReviewCard>> list(@RequestParam(required = false) String q,
        @RequestParam(required = false) Long guildId, @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(reviews.list(q, guildId, status, page, size));
    }

    @GetMapping("/reviews/{contentId}")
    @Operation(summary = "Đọc hồ sơ review và quyền thao tác hiện tại")
    @ApiResponse(responseCode = "200", description = "Hồ sơ còn quyền đọc; nháp chỉ cho người sửa.",
        useReturnTypeSchema = true)
    public BaseResponse<ReviewView> detail(@PathVariable @Positive Long contentId) {
        return BaseResponse.ok(reviews.detail(contentId));
    }

    @GetMapping("/reviews/{contentId}/threads")
    @Operation(summary = "Đọc góp ý theo phiên bản thiết kế")
    @ApiResponse(responseCode = "200", description = "Góp ý đúng revision và quyền hiện tại.",
        useReturnTypeSchema = true)
    public BaseResponse<PageResponse<ThreadView>> threads(@PathVariable @Positive Long contentId,
        @RequestParam @Positive Long revisionId, @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(reviews.threads(contentId, revisionId, status, page, size));
    }

    @GetMapping("/reviews/{contentId}/invitations")
    @Operation(summary = "Đọc lời mời phản biện")
    @ApiResponse(responseCode = "200", description = "Danh sách lời mời và quyền phản hồi của caller.",
        useReturnTypeSchema = true)
    public BaseResponse<PageResponse<InvitationView>> invitations(@PathVariable @Positive Long contentId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(reviews.invitations(contentId, page, size));
    }

    @PostMapping("/reviews")
    @Operation(summary = "Tạo đề nghị review kiến trúc")
    @ApiResponse(responseCode = "200", description = "Bản nháp đề nghị review.", useReturnTypeSchema = true)
    public BaseResponse<ContentDetail> create(@Valid @RequestBody CreateReview request) {
        return BaseResponse.ok(reviews.create(request));
    }

    @PostMapping("/reviews/{contentId}/invitations")
    @Operation(summary = "Mời phản biện thiết kế")
    @ApiResponse(responseCode = "200", description = "Snapshot người nhận đã có quyền đọc.", useReturnTypeSchema = true)
    public BaseResponse<List<InvitationView>> invite(@PathVariable @Positive Long contentId,
        @Valid @RequestBody Invite request) {
        return BaseResponse.ok(reviews.invite(contentId, request));
    }

    @PutMapping("/review-invitations/{invitationId}")
    @Operation(summary = "Phản hồi lời mời review")
    @ApiResponse(responseCode = "200", description = "Trạng thái lời mời sau kiểm version.", useReturnTypeSchema = true)
    public BaseResponse<InvitationView> respond(@PathVariable @Positive Long invitationId,
        @Valid @RequestBody Respond request) {
        return BaseResponse.ok(reviews.respond(invitationId, request));
    }

    @PostMapping("/reviews/{contentId}/threads")
    @Operation(summary = "Góp ý vào phiên bản thiết kế")
    @ApiResponse(responseCode = "200", description = "Thread và bình luận gốc ghi nguyên tử.",
        useReturnTypeSchema = true)
    public BaseResponse<ThreadView> thread(@PathVariable @Positive Long contentId,
        @Valid @RequestBody CreateThread request) {
        return BaseResponse.ok(reviews.thread(contentId, request));
    }

    @PutMapping("/review-threads/{threadId}")
    @Operation(summary = "Giải quyết hoặc mở lại góp ý")
    @ApiResponse(responseCode = "200", description = "Thread còn gắn revision nguồn.", useReturnTypeSchema = true)
    public BaseResponse<ThreadView> threadAction(@PathVariable @Positive Long threadId,
        @Valid @RequestBody ThreadAction request) {
        return BaseResponse.ok(reviews.threadAction(threadId, request));
    }

    @PostMapping("/reviews/{contentId}/conclude")
    @Operation(summary = "Chốt kết luận review")
    @ApiResponse(responseCode = "200", description = "Kết luận bất biến đúng current revision.",
        useReturnTypeSchema = true)
    public BaseResponse<ReviewView> conclude(@PathVariable @Positive Long contentId,
        @Valid @RequestBody Conclude request) {
        return BaseResponse.ok(reviews.conclude(contentId, request));
    }

    @PostMapping("/reviews/{contentId}/close")
    @Operation(summary = "Đóng đề nghị review")
    @ApiResponse(responseCode = "200", description = "Đề nghị chỉ đọc sau khi đóng.", useReturnTypeSchema = true)
    public BaseResponse<ReviewView> close(@PathVariable @Positive Long contentId, @Valid @RequestBody Close request) {
        return BaseResponse.ok(reviews.close(contentId, request));
    }

    @PostMapping("/reviews/{contentId}/follow-up")
    @Operation(summary = "Tạo vòng review tiếp theo")
    @ApiResponse(responseCode = "200", description = "Nội dung mới liên kết bản đã chốt.", useReturnTypeSchema = true)
    public BaseResponse<ContentDetail> followUp(@PathVariable @Positive Long contentId,
        @Valid @RequestBody FollowUpReview request) {
        return BaseResponse.ok(reviews.followUp(contentId, request));
    }
}
