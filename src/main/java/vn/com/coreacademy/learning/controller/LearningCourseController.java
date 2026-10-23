package vn.com.coreacademy.learning.controller;

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
import org.springframework.web.bind.annotation.PatchMapping;
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
import vn.com.coreacademy.learning.service.LearningContentService;

@RestController
@RequestMapping(value = "/api/v1/courses", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningCourseController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningCourseController {
    private final LearningContentService content;
    private final LearningAssignmentService assignments;

    @PostMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạo khóa học và bản nháp đầu tiên",
        description = """
            Tạo khóa thuộc phạm vi giảng viên, mã khóa duy nhất và chủ sở hữu do backend xác định.
            """
    )
    public BaseResponse<LearningDetails.CourseDetail> create(
        @Valid @RequestBody LearningRequests.CreateCourse request) {
        return BaseResponse.ok(content.create(request));
    }

    @GetMapping("/{courseId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xem cây nội dung để biên soạn",
        description = """
            Chỉ người có quyền sửa khóa được xem bản nháp; không gửi revisionId sẽ lấy bản mới nhất.
            """
    )
    public BaseResponse<LearningDetails.CourseDetail> get(@PathVariable @Positive Long courseId,
                                                          @RequestParam(required = false) @Positive Long revisionId) {
        return BaseResponse.ok(content.get(courseId, revisionId));
    }

    @PutMapping("/{courseId}/revisions/{revisionId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Lưu toàn bộ nội dung bản nháp",
        description = """
            PUT thay toàn bộ cây nháp và cấp lại ID chương/bài/khối; thứ tự theo mảng. Gửi expectedVersion mới nhất.
            """
    )
    public BaseResponse<LearningDetails.CourseDetail> save(@PathVariable @Positive Long courseId,
                                                           @PathVariable @Positive Long revisionId,
                                                           @Valid @RequestBody LearningRequests.DraftRequest request) {
        return BaseResponse.ok(content.saveDraft(courseId, revisionId, request));
    }

    @PostMapping("/{courseId}/revisions/{revisionId}/publish")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xuất bản khóa học",
        description = """
            Kiểm tra nội dung, danh mục và tài nguyên trước khi đóng băng revision. Không reset tiến độ cũ.
            """
    )
    public BaseResponse<LearningDetails.CourseDetail> publish(@PathVariable @Positive Long courseId,
        @PathVariable @Positive Long revisionId, @Valid @RequestBody LearningRequests.VersionRequest request) {
        return BaseResponse.ok(content.publish(courseId, revisionId, request));
    }

    @PostMapping("/{courseId}/draft")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Mở bản nháp để chỉnh sửa",
        description = """
            Tái sử dụng draft hiện có hoặc clone từ bản published; không sao chép tiến độ.
            """
    )
    public BaseResponse<LearningDetails.CourseDetail> draft(@PathVariable @Positive Long courseId) {
        return BaseResponse.ok(content.draft(courseId));
    }

    @PostMapping("/{courseId}/copy")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Sao chép thành khóa mới",
        description = """
            Cấp mã mới và clone nội dung thành draft; không copy người học, giao học hoặc chia sẻ.
            """
    )
    public BaseResponse<LearningDetails.CourseDetail> copy(@PathVariable @Positive Long courseId,
                                                           @Valid @RequestBody LearningRequests.CopyRequest request) {
        return BaseResponse.ok(content.copy(courseId, request));
    }

    @PatchMapping("/{courseId}/state")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạm dừng hoặc lưu trữ khóa học",
        description = """
            Nhận active/paused/archived và expectedVersion của courses; giữ lịch sử học.
            """
    )
    public BaseResponse<LearningViews.CourseView> state(@PathVariable @Positive Long courseId,
                                                        @Valid @RequestBody LearningRequests.CourseState request) {
        return BaseResponse.ok(content.state(courseId, request));
    }

    @GetMapping("/{courseId}/shares")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xem chia sẻ khóa học",
        description = """
            Chỉ người quản lý nội dung khóa được xem và sửa quyền khai thác theo đơn vị.
            """
    )
    public BaseResponse<List<LearningViews.CourseShareView>> shares(@PathVariable @Positive Long courseId) {
        return BaseResponse.ok(assignments.shares(courseId));
    }

    @PutMapping("/{courseId}/shares")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Chia sẻ hoặc thu hồi chia sẻ",
        description = """
            Chia sẻ cấp quyền khai thác, không cấp quyền sửa nội dung. Thu hồi không xóa giao học cũ.
            """
    )
    public BaseResponse<LearningViews.CourseShareView> share(@PathVariable @Positive Long courseId,
        @Valid @RequestBody LearningRequests.ShareRequest request) {
        return BaseResponse.ok(assignments.share(courseId, request));
    }

    @PostMapping("/{courseId}/assignments")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Giao khóa học cho cá nhân/nhóm/đơn vị",
        description = """
            Chốt người nhận khi giao, giới hạn 500 học viên/đợt và giữ idempotencyKey khi retry cùng payload.
            """
    )
    public BaseResponse<LearningDetails.AssignmentDetail> assign(@PathVariable @Positive Long courseId,
        @Valid @RequestBody LearningRequests.AssignmentRequest request) {
        return BaseResponse.ok(assignments.assign(courseId, request));
    }

    @GetMapping("/{courseId}/assignments")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xem các đợt giao của đơn vị",
        description = """
            Kiểm tra phạm vi assigningOrgId và quyền khai thác khóa trước phân trang.
            """
    )
    public BaseResponse<PageResponse<LearningViews.CourseAssignmentView>> assignments(
        @PathVariable @Positive Long courseId, @RequestParam @Positive Long assigningOrgId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(assignments.list(courseId, assigningOrgId, page, size));
    }

    @PostMapping("/{courseId}/enrollments")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tự đăng ký khóa học tự chọn",
        description = """
            Học viên tự đăng ký bản published được phép khám phá; giữ lần học tương thích nếu đã có.
            """
    )
    public BaseResponse<LearningViews.CourseEnrollmentView> enroll(@PathVariable @Positive Long courseId) {
        return BaseResponse.ok(assignments.selfEnroll(courseId));
    }

}
