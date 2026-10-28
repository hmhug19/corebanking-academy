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
import vn.com.coreacademy.community.dto.NotificationDtos.DigestDetail;
import vn.com.coreacademy.community.dto.NotificationDtos.DigestView;
import vn.com.coreacademy.community.dto.NotificationDtos.NotificationView;
import vn.com.coreacademy.community.dto.NotificationDtos.Retry;
import vn.com.coreacademy.community.service.CommunityNotificationService;

@RestController
@RequestMapping(value = "/api/v1/community/me", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community notifications")
public class CommunityNotificationController {
    private final CommunityNotificationService notifications;

    @GetMapping("/notifications")
    @Operation(summary = "Xem thông báo cá nhân")
    @ApiResponse(responseCode = "200", description = "Thông báo còn quyền nguồn hiện tại.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<NotificationView>> list(
        @RequestParam(defaultValue = "false") boolean unreadOnly, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(notifications.list(unreadOnly, page, size));
    }

    @PostMapping("/notifications/{notificationId}/read")
    @Operation(summary = "Đánh dấu đã đọc thông báo")
    @ApiResponse(responseCode = "200", description = "Thông báo của caller, repeat an toàn.",
        useReturnTypeSchema = true)
    public BaseResponse<NotificationView> read(@PathVariable @Positive Long notificationId) {
        return BaseResponse.ok(notifications.read(notificationId));
    }

    @GetMapping("/digests")
    @Operation(summary = "Xem các kỳ bản tin cá nhân")
    @ApiResponse(responseCode = "200", description = "Bản tin và số lượng item còn quyền.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<DigestView>> digests(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(notifications.batches(page, size));
    }

    @GetMapping("/digests/{batchId}")
    @Operation(summary = "Đọc nội dung bản tin")
    @ApiResponse(responseCode = "200", description = "Các item lọc ACL trước phân trang.", useReturnTypeSchema = true)
    public BaseResponse<DigestDetail> digest(@PathVariable @Positive Long batchId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(notifications.detail(batchId, page, size));
    }

    @PostMapping("/digests/{batchId}/read")
    @Operation(summary = "Đánh dấu đã đọc bản tin")
    @ApiResponse(responseCode = "200", description = "Chỉ đánh dấu các item còn quyền đọc.", useReturnTypeSchema = true)
    public BaseResponse<DigestView> readDigest(@PathVariable @Positive Long batchId) {
        return BaseResponse.ok(notifications.readBatch(batchId));
    }

    @PostMapping("/digests/{batchId}/retry")
    @Operation(summary = "Yêu cầu thử lại bản tin lỗi")
    @ApiResponse(responseCode = "200", description = "Mở lượt retry có version và audit.", useReturnTypeSchema = true)
    public BaseResponse<DigestView> retry(@PathVariable @Positive Long batchId, @Valid @RequestBody Retry request) {
        return BaseResponse.ok(notifications.retry(batchId, request));
    }
}
