package vn.com.coreacademy.learning.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.constants.LearningCodes.SearchMode;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.service.LearningSearchService;

@RestController
@RequestMapping(value = "/api/v1/instructor", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningInstructorController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningInstructorController {
    private final LearningSearchService search;

    @GetMapping("/courses")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách khóa học giảng viên",
        description = """
            Lọc owner/scope trước count và phân trang; số người được giao là số user khác nhau.
            """
    )
    public BaseResponse<PageResponse<LearningDetails.CourseCard>> courses(@RequestParam(required = false) String q,
        @RequestParam(required = false) Long fieldId, @RequestParam(required = false) Long programId,
        @RequestParam(required = false) Long moduleId, @RequestParam(required = false) String level,
        @RequestParam(required = false) String requirement, @RequestParam(required = false) String learningStatus,
        @RequestParam(required = false) String state, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "id,asc") String sort) {
        return BaseResponse.ok(search.search(SearchMode.MANAGE, q, fieldId, programId, moduleId, level, requirement,
            learningStatus, state, page, size, sort));
    }

}
