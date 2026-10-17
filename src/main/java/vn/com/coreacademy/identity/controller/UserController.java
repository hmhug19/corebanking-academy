package vn.com.coreacademy.identity.controller;

import java.net.URI;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.identity.dto.CreateUserRequest;
import vn.com.coreacademy.identity.dto.CreatedGrant;
import vn.com.coreacademy.identity.dto.GrantRequest;
import vn.com.coreacademy.identity.dto.UserDetail;
import vn.com.coreacademy.identity.dto.UserStatusRequest;
import vn.com.coreacademy.identity.dto.UserSummary;
import vn.com.coreacademy.identity.dto.UserUpdateRequest;
import vn.com.coreacademy.identity.service.RoleGrantService;
import vn.com.coreacademy.identity.service.UserAdministrationService;

@RestController
@RequestMapping(value = "/api/v1/users", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Users",
    description = "Quản lý hồ sơ, trạng thái, tổ chức và phân quyền của người dùng theo phạm vi được cấp.")
@StandardApiErrors
@RequiredArgsConstructor
public class UserController {
    private final UserAdministrationService users;
    private final RoleGrantService grants;

    @GetMapping
    @Parameters({
        @Parameter(name = "q", in = ParameterIn.QUERY,
            description = "Tìm theo mã cán bộ, username, họ tên hoặc email; tối đa 100 ký tự."),
        @Parameter(name = "roleCode", in = ParameterIn.QUERY,
            description = "Lọc theo mã vai trò của các phân quyền được phép xem."),
        @Parameter(name = "orgId", in = ParameterIn.QUERY,
            description = "Lọc tổ chức được chọn và các tổ chức con đang hoạt động trong phạm vi quản lý."),
        @Parameter(name = "teamId", in = ParameterIn.QUERY,
            description = "Lọc thành viên của nhóm thuộc phạm vi quản lý."),
        @Parameter(name = "status", in = ParameterIn.QUERY,
            description = "Trạng thái: active (hoạt động), inactive (tạm dừng), locked (khóa)."),
        @Parameter(name = "page", in = ParameterIn.QUERY, description = "Chỉ số trang, bắt đầu từ 0."),
        @Parameter(name = "size", in = ParameterIn.QUERY,
            description = "Số bản ghi mỗi trang, từ 1 đến 100; mặc định 20."),
        @Parameter(name = "sort", in = ParameterIn.QUERY,
            description = "Dạng field,asc hoặc field,desc; field: id, username, fullName, createTime.")
    })
    @Operation(
        summary = "Tra cứu danh sách người dùng",
        description = """
            Tra cứu người dùng trong phạm vi quản lý, có tìm kiếm, lọc và phân trang.
            - Phạm vi quyền được áp dụng trước khi phân trang và tính tổng.
            - Lọc theo vai trò chỉ xét các phân quyền người gọi được phép xem.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<PageResponse<UserSummary>>> list(@RequestParam(required = false) String q,
        @RequestParam(required = false) String roleCode, @RequestParam(required = false) Long orgId,
        @RequestParam(required = false) Long teamId, @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "fullName,asc") String sort) {
        return ResponseEntity.ok(BaseResponse.ok(users.list(q, roleCode, orgId, teamId, status, page, size, sort)));
    }

    @PostMapping
    @Operation(
        summary = "Tạo tài khoản người dùng",
        description = """
            Tạo tài khoản nội bộ (LOCAL), kèm tổ chức, nhóm và phân quyền ban đầu nếu có.
            - Tài khoản mới ở trạng thái `active` và phải đổi mật khẩu tạm sau đăng nhập.
            - Không tự cấp vai trò mặc định; dữ liệu không hợp lệ sẽ hủy toàn bộ thao tác tạo.
            """
    )
    @ApiResponse(responseCode = "200", description = "status=ERROR khi validation hoặc nghiệp vụ không hợp lệ.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<UserDetail>> create(@Valid @RequestBody CreateUserRequest request) {
        UserDetail result = users.create(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + result.user().userId()))
            .body(BaseResponse.ok(result));
    }

    @GetMapping("/{userId}")
    @Operation(
        summary = "Lấy thông tin chi tiết người dùng",
        description = """
            Trả hồ sơ, tổ chức, nhóm và các phân quyền được phép xem của người dùng.
            - `editable` và `revocable` hỗ trợ hiển thị thao tác; backend vẫn kiểm tra quyền khi thực hiện.
            - `userId` là mã cán bộ dạng chuỗi, cần giữ nguyên số 0 ở đầu.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<UserDetail>> get(@PathVariable String userId) {
        return ResponseEntity.ok(BaseResponse.ok(users.get(userId)));
    }

    @PatchMapping("/{userId}")
    @Operation(
        summary = "Cập nhật hồ sơ và tổ chức của người dùng",
        description = """
            Cập nhật hồ sơ và tổ chức, nhóm của tài khoản LOCAL trong phạm vi quản lý.
            - Chỉ SUPER_ADMIN được quản lý tài khoản mang vai trò ADMIN hoặc SUPER_ADMIN.
            - Đổi tổ chức/nhóm sẽ thu hồi mọi phân quyền khác `OWN`; người dùng phải đăng nhập lại.
            - Không cho phép tự chuyển tổ chức/nhóm; không thay đổi username, trạng thái hoặc mật khẩu.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<UserDetail>> update(@PathVariable String userId,
        @Valid @RequestBody UserUpdateRequest request) {
        return ResponseEntity.ok(BaseResponse.ok(users.update(userId, request)));
    }

    @PatchMapping("/{userId}/status")
    @Operation(
        summary = "Cập nhật trạng thái tài khoản",
        description = """
            Kích hoạt, tạm dừng hoặc khóa tài khoản trong phạm vi quản lý.
            - Chỉ SUPER_ADMIN được đổi trạng thái tài khoản mang vai trò quản trị.
            - Khi trạng thái thay đổi, mọi token cũ của người dùng bị thu hồi.
            - Không được tự tạm dừng/khóa hoặc vô hiệu hóa SUPER_ADMIN cuối cùng.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<UserDetail>> status(@PathVariable String userId,
        @Valid @RequestBody UserStatusRequest request) {
        return ResponseEntity.ok(BaseResponse.ok(users.status(userId, request.status())));
    }

    @PostMapping("/{userId}/role-grants")
    @Operation(
        summary = "Gán vai trò và phạm vi dữ liệu cho người dùng",
        description = """
            Gán một vai trò kèm phạm vi dữ liệu cho người dùng.
            - Chọn vai trò và phạm vi được phép cấp từ `GET /api/v1/roles`.
            - Không tự cấp hoặc cấp vượt quyền; `ADMIN`/`SUPER_ADMIN` chỉ do SUPER_ADMIN cấp.
            - Thành công thu hồi token cũ; phân quyền trùng trả mã `IDENTITY-ROLE_GRANT_EXISTS`.
            """
    )
    @ApiResponse(responseCode = "200", description = "status=ERROR khi validation hoặc nghiệp vụ không hợp lệ.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<CreatedGrant>> grant(@PathVariable String userId,
        @Valid @RequestBody GrantRequest request) {
        Long grantId = grants.add(userId, request);
        return ResponseEntity.status(201).body(BaseResponse.ok(new CreatedGrant(grantId)));
    }

    @DeleteMapping("/{userId}/role-grants/{grantId}")
    @Operation(
        summary = "Thu hồi phân quyền của người dùng",
        description = """
            Thu hồi một phân quyền của người dùng và giữ lại lịch sử.
            - Token cũ của người dùng bị thu hồi sau khi thành công.
            - Không tự thu hồi hoặc làm mất quyền SUPER_ADMIN cuối cùng.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<Void>> revoke(@PathVariable String userId, @PathVariable Long grantId) {
        grants.revoke(userId, grantId);
        return ResponseEntity.ok(BaseResponse.ok());
    }
}
