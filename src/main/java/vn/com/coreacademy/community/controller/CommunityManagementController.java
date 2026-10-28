package vn.com.coreacademy.community.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.CommunityManagementDtos.DuplicateQuestion;
import vn.com.coreacademy.community.dto.CommunityManagementDtos.Maintainer;
import vn.com.coreacademy.community.dto.CommunityManagementDtos.MergeTag;
import vn.com.coreacademy.community.dto.CommunityManagementDtos.PinRequest;
import vn.com.coreacademy.community.dto.CommunityManagementDtos.PinView;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.TagView;
import vn.com.coreacademy.community.service.CommunityManagementService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Community management")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CommunityManagementController {
    private final CommunityManagementService management;

    @PostMapping("/tags/{tagId}/merge")
    @ApiResponse(responseCode = "200", description = "Tag nguồn chuyển merged, quan hệ đã gộp.",
        useReturnTypeSchema = true)
    @Operation(summary = "Gộp tag trong cùng đơn vị")
    public BaseResponse<TagView> merge(@PathVariable @Positive Long tagId, @Valid @RequestBody MergeTag request) {
        return BaseResponse.ok(management.merge(tagId, request));
    }

    @PutMapping("/contents/{contentId}/maintainer")
    @ApiResponse(responseCode = "200", description = "Nội dung và maintainer mới.", useReturnTypeSchema = true)
    @Operation(summary = "Chuyển người duy trì nội dung")
    public BaseResponse<ContentDetail> maintainer(@PathVariable @Positive Long contentId,
        @Valid @RequestBody Maintainer request) {
        return BaseResponse.ok(management.maintainer(contentId, request));
    }

    @PutMapping("/questions/{questionId}/duplicate")
    @ApiResponse(responseCode = "200", description = "Question cập nhật duplicate, không tự reopen khi bỏ đánh dấu.",
        useReturnTypeSchema = true)
    @Operation(summary = "Đánh dấu hoặc bỏ đánh dấu câu hỏi trùng")
    public BaseResponse<ContentDetail> duplicate(@PathVariable @Positive Long questionId,
        @Valid @RequestBody DuplicateQuestion request) {
        return BaseResponse.ok(management.duplicate(questionId, request));
    }

    @PutMapping("/guilds/{guildId}/pins/{contentId}")
    @ApiResponse(responseCode = "200", description = "SET ghim, không mở quyền nội dung.", useReturnTypeSchema = true)
    @Operation(summary = "Ghim hoặc bỏ ghim nội dung trong Guild")
    public BaseResponse<PinView> pin(@PathVariable @Positive Long guildId, @PathVariable @Positive Long contentId,
        @Valid @RequestBody PinRequest request) {
        return BaseResponse.ok(management.pin(guildId, contentId, request.pinned()));
    }
}
