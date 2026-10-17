package vn.com.coreacademy.identity.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.identity.enums.ScopeType;

public record RoleOption(
    Long roleId, String code, String name, String description,
    @Schema(description = "Các phạm vi người gọi được cấp cho vai trò này; rỗng nghĩa là không được cấp.")
    List<ScopeType> assignableScopes,
    @Schema(description = "Hành động theo vai trò; vẫn phụ thuộc scope, tài nguyên và policy tại backend.")
    List<String> capabilities,
    @Schema(description = "Chức năng giao diện; không tự cấp quyền tạo, sửa hoặc xóa dữ liệu.")
    List<FunctionInfo> functions) {
}
