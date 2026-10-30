package vn.com.coreacademy.community.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.constants.CommunityLimits;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.ModerationRequests.ModeratorRequest;
import vn.com.coreacademy.community.dto.ModerationRequests.ModeratorView;
import vn.com.coreacademy.community.service.CommunityModeratorService;

@RestController
@RequestMapping(value = "/api/v1/community/moderation", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Community moderation")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CommunityModeratorController {
    private final CommunityModeratorService moderators;

    @GetMapping("/moderators")
    @ApiResponse(responseCode = "200", description = "Moderator của đơn vị theo trạng thái.",
        useReturnTypeSchema = true)
    @Operation(summary = "Danh sách org moderator", description = """
        ADMIN đúng scope, SUPER_ADMIN hoặc moderator còn hiệu lực của chính đơn vị.
        - status active (mặc định) hoặc revoked; version từng dòng dùng cho PUT cấp lại hoặc thu hồi.
        """)
    public BaseResponse<PageResponse<ModeratorView>> list(@RequestParam @Positive Long orgId,
        @RequestParam(defaultValue = "active") String status, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(moderators.list(orgId, status, page, size));
    }

    @GetMapping("/candidates")
    @ApiResponse(responseCode = "200", description = "Nhân sự đang hoạt động của đơn vị.", useReturnTypeSchema = true)
    @Operation(summary = "Nhân sự để chọn moderator hoặc người duy trì", description = """
        Cùng phạm vi như danh sách moderator: ADMIN đúng scope, SUPER_ADMIN hoặc moderator còn hiệu lực của đơn vị.
        - q lọc theo họ tên; chỉ trả userId và displayName.
        """)
    public BaseResponse<PageResponse<PersonRef>> candidates(@RequestParam @Positive Long orgId,
        @RequestParam(required = false) @Size(max = CommunityLimits.QUERY) String q,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(moderators.candidates(orgId, q, page, size));
    }

    @PutMapping("/moderators/{userId}")
    @ApiResponse(responseCode = "200", description = "Grant moderator đã cập nhật hoặc lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    @Operation(summary = "Cấp hoặc thu hồi org moderator", description = """
        ADMIN đúng scope hoặc SUPER_ADMIN điều phối org moderator.
        - Không cho tự cấp; không cấp role quản trị Identity hoặc quyền đọc toàn bộ bài private.
        """)
    public BaseResponse<ModeratorView> moderator(@PathVariable @Size(min = 1, max = 20) String userId,
        @Valid @RequestBody ModeratorRequest request) {
        return BaseResponse.ok(moderators.set(userId, request));
    }
}
