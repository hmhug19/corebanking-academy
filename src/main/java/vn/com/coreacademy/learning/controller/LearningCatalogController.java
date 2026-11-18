package vn.com.coreacademy.learning.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.service.LearningCatalogService;

@RestController
@RequestMapping(value = "/api/v1/training-catalog", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningCatalogController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningCatalogController {
    private final LearningCatalogService catalog;

    @GetMapping("/tree")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cây danh mục đào tạo",
        description = """
            Lĩnh vực, chương trình và khóa học của một đơn vị, sắp theo thời gian tạo tăng dần rồi id.
            Mỗi lĩnh vực và chương trình trả đủ trường (kể cả rowVersion) để cập nhật trạng thái bằng PUT.
            Khóa chưa xếp chương trình không có trong cây.
            """
    )
    public BaseResponse<LearningViews.CatalogTreeView> tree(@RequestParam @Positive Long ownerOrgId) {
        return BaseResponse.ok(catalog.tree(ownerOrgId));
    }

    @GetMapping("/fields")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách fields",
        description = """
            Danh mục và tổng số bản ghi được lọc theo quyền của người gọi trước khi phân trang.
            """
    )
    public BaseResponse<PageResponse<LearningViews.TrainingFieldView>> fields(@RequestParam @Positive Long ownerOrgId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(catalog.fields(ownerOrgId, page, size));
    }

    @PostMapping("/fields")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạo fields",
        description = """
            Tạo lĩnh vực và các phân hệ tùy chọn trong cùng transaction.
            - modules[] chỉ chứa dòng mới (không có id); mã phân hệ không trùng trong lĩnh vực.
            """
    )
    public BaseResponse<LearningViews.TrainingFieldView> createField(
        @Valid @RequestBody LearningRequests.FieldSaveRequest request) {
        return BaseResponse.ok(catalog.saveFieldWithModules(null, null, request));
    }

    @PutMapping("/fields/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cập nhật fields",
        description = """
            Cập nhật lĩnh vực và các thao tác modules[] tùy chọn trong cùng transaction.
            - Dòng cũ cần id và expectedVersion; deleted=true để xóa phân hệ chưa được dùng.
            - Bỏ modules không thay đổi phân hệ; lỗi một dòng rollback cả lĩnh vực và các dòng khác.
            """
    )
    public BaseResponse<LearningViews.TrainingFieldView> updateField(@PathVariable @Positive Long id,
        @RequestParam @Min(0) Long expectedVersion, @Valid @RequestBody LearningRequests.FieldSaveRequest request) {
        return BaseResponse.ok(catalog.saveFieldWithModules(id, expectedVersion, request));
    }

    @DeleteMapping("/fields/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xóa lĩnh vực trống",
        description = """
            Chỉ xóa khi lĩnh vực không còn chương trình hay phân hệ; ngược lại trả LEARNING-CATALOG_IN_USE và
            client chuyển sang Lưu trữ (PUT status=archived). expectedVersion chống xóa theo dữ liệu cũ.
            """
    )
    public BaseResponse<Void> deleteField(@PathVariable @Positive Long id,
                                          @RequestParam @Min(0) Long expectedVersion) {
        catalog.deleteField(id, expectedVersion);
        return BaseResponse.ok(null);
    }

    @DeleteMapping("/programs/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xóa chương trình trống",
        description = """
            Chỉ xóa khi chương trình không còn khóa học (kể cả bản nháp) hay câu hỏi trong ngân hàng; ngược lại trả
            LEARNING-CATALOG_IN_USE và client chuyển sang Lưu trữ (PUT status=archived).
            """
    )
    public BaseResponse<Void> deleteProgram(@PathVariable @Positive Long id,
                                            @RequestParam @Min(0) Long expectedVersion) {
        catalog.deleteProgram(id, expectedVersion);
        return BaseResponse.ok(null);
    }

    @GetMapping("/programs")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách programs",
        description = """
            Danh mục và tổng số bản ghi được lọc theo quyền của người gọi trước khi phân trang.
            """
    )
    public BaseResponse<PageResponse<LearningViews.TrainingProgramView>> programs(
        @RequestParam @Positive Long ownerOrgId, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(catalog.programs(ownerOrgId, page, size));
    }

    @PostMapping("/programs")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạo programs",
        description = """
            Mã được kiểm tra trùng trong transaction; các ID cha phải tồn tại và thuộc đúng tổ chức.
            """
    )
    public BaseResponse<LearningViews.TrainingProgramView> createProgram(
        @Valid @RequestBody LearningRequests.CatalogRequest request) {
        return BaseResponse.ok(catalog.saveProgram(null, null, request));
    }

    @PutMapping("/programs/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cập nhật programs",
        description = """
            Thay thông tin danh mục, không chuyển tổ chức/lĩnh vực; paused chặn học theo danh mục.
            """
    )
    public BaseResponse<LearningViews.TrainingProgramView> updateProgram(@PathVariable @Positive Long id,
        @RequestParam @Min(0) Long expectedVersion, @Valid @RequestBody LearningRequests.CatalogRequest request) {
        return BaseResponse.ok(catalog.saveProgram(id, expectedVersion, request));
    }

    @PostMapping("/programs/{id}/copy")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Sao chép chương trình",
        description = """
            Tạo chương trình nháp cùng lĩnh vực (mã gốc kèm -COPY, tên kèm "(bản sao)") và bản nháp của mọi khóa học
            trong chương trình; không sao chép người học, đợt giao hay chia sẻ. Tất cả trong một transaction.
            """
    )
    public BaseResponse<LearningViews.TrainingProgramView> copyProgram(@PathVariable @Positive Long id) {
        return BaseResponse.ok(catalog.copyProgram(id));
    }

    @GetMapping("/modules")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách modules",
        description = """
            Danh mục và tổng số bản ghi được lọc theo quyền của người gọi trước khi phân trang.
            """
    )
    public BaseResponse<PageResponse<LearningViews.BusinessModuleView>> modules(@RequestParam @Positive Long fieldId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(catalog.modules(fieldId, page, size));
    }

    @PostMapping("/modules")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạo modules",
        description = """
            Mã được kiểm tra trùng trong transaction; các ID cha phải tồn tại và thuộc đúng tổ chức.
            """
    )
    public BaseResponse<LearningViews.BusinessModuleView> createModule(
        @Valid @RequestBody LearningRequests.CatalogRequest request) {
        return BaseResponse.ok(catalog.saveModule(null, null, request));
    }

    @PutMapping("/modules/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cập nhật modules",
        description = """
            Thay thông tin danh mục, không chuyển tổ chức/lĩnh vực; paused chặn học theo danh mục.
            """
    )
    public BaseResponse<LearningViews.BusinessModuleView> updateModule(@PathVariable @Positive Long id,
        @RequestParam @Min(0) Long expectedVersion, @Valid @RequestBody LearningRequests.CatalogRequest request) {
        return BaseResponse.ok(catalog.saveModule(id, expectedVersion, request));
    }

    @DeleteMapping("/modules/{id}")
    @Operation(summary = "Xóa phân hệ nghiệp vụ", description = """
        Xóa phân hệ chưa được sử dụng trong bất kỳ phiên bản khóa học nào.
        - Kiểm tra phạm vi quản lý và expectedVersion; đang dùng trả LEARNING-CATALOG_IN_USE.
        """)
    public BaseResponse<Void> deleteModule(@PathVariable @Positive Long id,
                                          @RequestParam @Min(0) Long expectedVersion) {
        catalog.deleteModule(id, expectedVersion);
        return BaseResponse.ok(null);
    }

}
