package vn.com.coreacademy.learning.controller;

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
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.service.LearningAssignmentService;

@RestController
@RequestMapping(value = "/api/v1/learning-groups", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningGroupController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningGroupController {
    private final LearningAssignmentService assignments;

    @GetMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách nhóm học tập",
        description = """
            Chỉ trả nhóm của tổ chức trong phạm vi quản lý.
            """
    )
    public BaseResponse<PageResponse<LearningViews.LearningGroupView>> list(@RequestParam @Positive Long ownerOrgId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(assignments.groups(ownerOrgId, page, size));
    }

    @PostMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạo nhóm học tập",
        description = """
            Nhóm học độc lập nhóm chuyên môn, không tạo hay cấp role Identity.
            """
    )
    public BaseResponse<LearningDetails.GroupDetail> create(@Valid @RequestBody LearningRequests.GroupRequest request) {
        return BaseResponse.ok(assignments.createGroup(request));
    }

    @GetMapping("/{groupId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xem nhóm và thành viên",
        description = """
            Trả các membership đang hiệu lực; lịch sử rời nhóm vẫn được giữ.
            """
    )
    public BaseResponse<LearningDetails.GroupDetail> get(@PathVariable @Positive Long groupId) {
        return BaseResponse.ok(assignments.group(groupId));
    }

    @PutMapping("/{groupId}/members")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Thay danh sách thành viên nhóm học",
        description = """
            Thêm/rời nhóm trong một transaction; không thay snapshot người nhận của các đợt giao trước.
            """
    )
    public BaseResponse<LearningDetails.GroupDetail> members(@PathVariable @Positive Long groupId,
        @Valid @RequestBody LearningRequests.MembersRequest request) {
        return BaseResponse.ok(assignments.members(groupId, request));
    }

}
