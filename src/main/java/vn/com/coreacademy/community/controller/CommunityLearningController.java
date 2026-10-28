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
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.LearningResourceDtos.LearningResourceView;
import vn.com.coreacademy.community.dto.LearningResourceDtos.ResourceResult;
import vn.com.coreacademy.community.dto.LearningResourceDtos.Resources;
import vn.com.coreacademy.community.service.CommunityLearningService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community learning resources")
public class CommunityLearningController {
    private final CommunityLearningService learning;

    @GetMapping("/course-revisions/{courseRevisionId}/resources")
    @Operation(summary = "Đọc tài liệu tham khảo khóa học")
    @ApiResponse(responseCode = "200", description = "Kiểm đồng thời Learning và Community ACL.",
        useReturnTypeSchema = true)
    public BaseResponse<PageResponse<LearningResourceView>> list(@PathVariable @Positive Long courseRevisionId,
        @RequestParam(required = false) Long lessonId, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(learning.list(courseRevisionId, lessonId, page, size));
    }

    @PutMapping("/course-revisions/{courseRevisionId}/resources")
    @Operation(summary = "Ghim tài liệu Community vào khóa nháp")
    @ApiResponse(responseCode = "200", description = "Danh sách mới và version revision Learning.",
        useReturnTypeSchema = true)
    public BaseResponse<ResourceResult> replace(@PathVariable @Positive Long courseRevisionId,
        @Valid @RequestBody Resources request) {
        return BaseResponse.ok(learning.replace(courseRevisionId, request));
    }

    @GetMapping("/learning/reference-candidates")
    @Operation(summary = "Tìm nội dung có thể ghim vào khóa học")
    @ApiResponse(responseCode = "200", description = "Nguồn published caller có quyền công bố.",
        useReturnTypeSchema = true)
    public BaseResponse<PageResponse<ContentCard>> candidates(@RequestParam(required = false) String q,
        @RequestParam(required = false) String kind, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(learning.candidates(q, kind, page, size));
    }
}
