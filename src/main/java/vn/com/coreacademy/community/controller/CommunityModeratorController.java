package vn.com.coreacademy.community.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
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
