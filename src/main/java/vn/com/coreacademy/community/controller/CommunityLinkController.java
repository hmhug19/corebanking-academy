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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.LinkDtos.LinkResult;
import vn.com.coreacademy.community.dto.LinkDtos.LinkView;
import vn.com.coreacademy.community.dto.LinkDtos.Links;
import vn.com.coreacademy.community.service.CommunityLinkService;

@RestController
@RequestMapping(value = "/api/v1/community/contents", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community links")
public class CommunityLinkController {
    private final CommunityLinkService links;

    @GetMapping("/{contentId}/links")
    @Operation(summary = "Xem các tài liệu liên quan")
    @ApiResponse(responseCode = "200", description = "Cả nguồn và đích phải còn quyền đọc.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<LinkView>> list(@PathVariable @Positive Long contentId,
        @RequestParam(required = false) String relationType, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(links.list(contentId, relationType, page, size));
    }

    @PutMapping("/{contentId}/links")
    @Operation(summary = "Cập nhật liên kết tri thức")
    @ApiResponse(responseCode = "200", description = "SET có version, chặn self-link và supersedes cycle.",
        useReturnTypeSchema = true)
    public BaseResponse<LinkResult> replace(@PathVariable @Positive Long contentId, @Valid @RequestBody Links request) {
        return BaseResponse.ok(links.replace(contentId, request));
    }
}
