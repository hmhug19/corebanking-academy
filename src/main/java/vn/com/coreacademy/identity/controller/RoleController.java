package vn.com.coreacademy.identity.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.identity.dto.RoleOption;
import vn.com.coreacademy.identity.service.RoleGrantService;

@RestController
@Tag(name = "Roles", description = "Danh mục vai trò, phạm vi dữ liệu và chức năng phục vụ biểu mẫu phân quyền.")
@StandardApiErrors
@RequiredArgsConstructor
public class RoleController {
    private final RoleGrantService roles;

    @GetMapping(value = "/api/v1/roles", produces = "application/json")
    @Operation(
        summary = "Lấy danh mục vai trò và phạm vi được phép cấp",
        description = """
            Trả danh mục vai trò đang hoạt động để xây dựng biểu mẫu phân quyền.
            - Dùng `roleId` từ kết quả; không gán cố định theo ví dụ.
            - `assignableScopes` rỗng nghĩa là người gọi không được cấp vai trò đó.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<List<RoleOption>>> list() {
        return ResponseEntity.ok(BaseResponse.ok(roles.catalog()));
    }
}
