package vn.com.coreacademy.identity.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateUserRequest(
    @Schema(description = "Mã cán bộ dạng chuỗi, giữ nguyên số 0 đầu; không trùng mã hoặc username khác.")
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,20}") String userId,
    @Schema(description = "Tên đăng nhập, lưu dưới dạng chữ thường; không trùng các định danh đã có.")
    @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,100}") String username,
    @NotBlank @Size(max = 255) String fullName,
    @Email @Size(max = 100) String email,
    @Size(max = 255) String positionName,
    @Positive Long orgId,
    @Schema(description = "Nhóm chuyên môn thuộc đúng orgId của tài khoản.")
    @Positive Long teamId,
    @NotBlank @Size(min = 6, max = 72)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Schema(description = "Mật khẩu tạm: ít nhất 6 ký tự, tối đa 72 byte UTF-8; phải đổi sau đăng nhập.",
        accessMode = Schema.AccessMode.WRITE_ONLY) String initialPassword,
    @Schema(description = "Các phân quyền ban đầu; bỏ qua thì không tự gán vai trò mặc định.")
    @Size(max = 20) List<@NotNull @Valid GrantRequest> initialRoleGrants) {
    @Override
    public String toString() {
        return "CreateUserRequest[REDACTED]";
    }
}
