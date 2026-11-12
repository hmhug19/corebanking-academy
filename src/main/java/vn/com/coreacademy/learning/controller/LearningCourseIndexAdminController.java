package vn.com.coreacademy.learning.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.FailedRevision;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;
import vn.com.coreacademy.learning.service.XpAdjustmentService;

@RestController
@RequestMapping(value = "/api/v1/admin/learning/course-index", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningCourseIndexAdminController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningCourseIndexAdminController {
    private final CourseContentChunkRepository index;
    private final LearningAccessPolicy access;

    @GetMapping("/failures")
    @Operation(
        summary = "Revision khóa học lập chỉ mục lỗi",
        description = """
            Các revision mà trợ lý AI chưa lập chỉ mục được: status=retry là đang chờ thử lại (attempts lần lỗi),
            status=failed là đã hết số lần thử và chờ quản trị viên gọi retry. errorType là tên lớp lỗi, không chứa
            nội dung khóa học. Chỉ quản trị hệ thống (ADMIN hoặc SUPER_ADMIN, phạm vi GLOBAL); vai trò khác nhận 403.
            """
    )
    public BaseResponse<List<FailedRevision>> failures() {
        requireSystemAdmin();

        return BaseResponse.ok(index.failed());
    }

    @PostMapping("/{revisionId}/retry")
    @Operation(
        summary = "Thử lập chỉ mục lại một revision",
        description = """
            Đưa revision đang lỗi (retry hoặc failed) về hàng đợi; lượt chạy kế tiếp của worker sẽ lập chỉ mục lại
            và đếm lại từ 0. Revision không ở trạng thái lỗi trả 404. Chỉ quản trị hệ thống (phạm vi GLOBAL).
            """
    )
    public BaseResponse<Boolean> retry(@PathVariable long revisionId) {
        requireSystemAdmin();
        if (!index.retry(revisionId)) {
            throw BusinessException.validation("revisionId", "NOT_FOUND", "Revision không có lỗi lập chỉ mục.");
        }

        return BaseResponse.ok(true);
    }

    private void requireSystemAdmin() {
        if (!XpAdjustmentService.isSystemAdmin(access.actor())) {
            throw new AccessDeniedException("System admin required");
        }
    }
}
