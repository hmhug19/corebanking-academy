package vn.com.coreacademy.organization.controller;

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
import vn.com.coreacademy.organization.dto.CreateOrganizationRequest;
import vn.com.coreacademy.organization.dto.CreateTeamRequest;
import vn.com.coreacademy.organization.dto.OrganizationDetail;
import vn.com.coreacademy.organization.dto.OrganizationInfo;
import vn.com.coreacademy.organization.dto.OrganizationUpdateRequest;
import vn.com.coreacademy.organization.dto.TeamInfo;
import vn.com.coreacademy.organization.service.OrganizationAdministrationService;
import vn.com.coreacademy.organization.service.TeamAdministrationService;

@RestController
@RequestMapping(value = "/api/v1/orgs", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Organizations", description = "Quản lý cây đơn vị, phòng và các nhóm trực thuộc theo phạm vi được cấp.")
@StandardApiErrors
@RequiredArgsConstructor
public class OrganizationController {
    private final OrganizationAdministrationService organizations;
    private final TeamAdministrationService teams;

    @GetMapping
    @Parameters({
        @Parameter(name = "q", in = ParameterIn.QUERY, description = "Tìm theo mã hoặc tên tổ chức; tối đa 100 ký tự."),
        @Parameter(name = "parentId", in = ParameterIn.QUERY,
            description = "Lấy các nút con trực tiếp; không kết hợp với rootOnly=true."),
        @Parameter(name = "rootOnly", in = ParameterIn.QUERY,
            description = "true để lấy các nút gốc trong phạm vi quản lý; false mặc định."),
        @Parameter(name = "orgType", in = ParameterIn.QUERY,
            description = "Loại tổ chức: UNIT (đơn vị) hoặc DEPARTMENT (phòng)."),
        @Parameter(name = "status", in = ParameterIn.QUERY, description = "Trạng thái tổ chức: active hoặc inactive."),
        @Parameter(name = "page", in = ParameterIn.QUERY, description = "Chỉ số trang, bắt đầu từ 0."),
        @Parameter(name = "size", in = ParameterIn.QUERY,
            description = "Số bản ghi mỗi trang, từ 1 đến 100; mặc định 20."),
        @Parameter(name = "sort", in = ParameterIn.QUERY,
            description = "Dạng field,asc hoặc field,desc; field: id, code, name, sortNo.")
    })
    @Operation(
        summary = "Tra cứu danh sách và cây tổ chức",
        description = """
            Tra cứu đơn vị/phòng trong phạm vi quản lý dưới dạng danh sách hoặc cây.
            - Mỗi cấp của cây đều có phân trang; cần tải tiếp khi còn trang.
            - Không kết hợp `rootOnly=true` với `parentId`.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<PageResponse<OrganizationInfo>>> list(@RequestParam(required = false) String q,
        @RequestParam(required = false) Long parentId, @RequestParam(defaultValue = "false") boolean rootOnly,
        @RequestParam(required = false) String orgType, @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "sortNo,asc") String sort) {
        return ResponseEntity.ok(BaseResponse.ok(organizations.list(
            q, parentId, rootOnly, orgType, status, page, size, sort)));
    }

    @PostMapping
    @Operation(
        summary = "Tạo đơn vị hoặc phòng",
        description = """
            Tạo đơn vị hoặc phòng ở trạng thái `active`.
            - Chỉ quản trị viên toàn hệ thống được thực hiện.
            - Hiện chưa hỗ trợ chuyển vị trí hoặc đổi loại tổ chức sau khi tạo.
            """
    )
    @ApiResponse(responseCode = "200", description = "status=ERROR khi validation hoặc nghiệp vụ không hợp lệ.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<OrganizationInfo>> create(
        @Valid @RequestBody CreateOrganizationRequest request) {
        var result = organizations.create(request);
        return ResponseEntity.created(URI.create("/api/v1/orgs/" + result.id())).body(BaseResponse.ok(result));
    }

    @GetMapping("/{orgId}")
    @Operation(
        summary = "Lấy thông tin chi tiết tổ chức",
        description = """
            Trả thông tin tổ chức, số thành viên/nhóm trực thuộc và tối đa 20 quản trị viên gán trực tiếp.
            - Số lượng không cộng dồn từ tổ chức con.
            - Danh sách quản trị viên không gồm quyền GLOBAL hoặc quyền kế thừa từ cấp trên.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<OrganizationDetail>> get(@PathVariable Long orgId) {
        return ResponseEntity.ok(BaseResponse.ok(organizations.get(orgId)));
    }

    @PatchMapping("/{orgId}")
    @Operation(
        summary = "Cập nhật thông tin tổ chức",
        description = """
            Cập nhật thông tin tổ chức trong phạm vi quản lý.
            - Chỉ quản trị viên toàn hệ thống được thay đổi trạng thái.
            - Tổ chức inactive chặn quyền ORG qua node đó và cây con; không tự đổi trạng thái tài khoản.
            - Không thay đổi tổ chức cha hoặc loại tổ chức.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<OrganizationInfo>> update(@PathVariable Long orgId,
        @Valid @RequestBody OrganizationUpdateRequest request) {
        return ResponseEntity.ok(BaseResponse.ok(organizations.update(orgId, request)));
    }

    @GetMapping("/{orgId}/teams")
    @Parameters({
        @Parameter(name = "page", in = ParameterIn.QUERY, description = "Chỉ số trang, bắt đầu từ 0."),
        @Parameter(name = "size", in = ParameterIn.QUERY,
            description = "Số bản ghi mỗi trang, từ 1 đến 100; mặc định 20."),
        @Parameter(name = "sort", in = ParameterIn.QUERY,
            description = "Dạng field,asc hoặc field,desc; field: id, code, name.")
    })
    @Operation(
        summary = "Lấy danh sách nhóm thuộc tổ chức",
        description = """
            Trả danh sách nhóm trực thuộc tổ chức, có phân trang và tối đa 5 thành viên xem trước mỗi nhóm.
            - Xem đầy đủ thành viên qua `GET /api/v1/users?teamId=<id>` và xử lý phân trang.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<PageResponse<TeamInfo>>> teams(@PathVariable Long orgId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "name,asc") String sort) {
        return ResponseEntity.ok(BaseResponse.ok(teams.list(orgId, page, size, sort)));
    }

    @PostMapping("/{orgId}/teams")
    @Operation(
        summary = "Tạo nhóm chuyên môn trong tổ chức",
        description = """
            Tạo nhóm chuyên môn trong đơn vị/phòng đang hoạt động thuộc phạm vi quản lý.
            - Mã nhóm nếu có không được trùng trong cùng tổ chức.
            - Thao tác tạo không tự gán thành viên.
            """
    )
    @ApiResponse(responseCode = "200", description = "status=ERROR khi validation hoặc nghiệp vụ không hợp lệ.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
    @ApiResponse(responseCode = "201", useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<TeamInfo>> createTeam(@PathVariable Long orgId,
        @Valid @RequestBody CreateTeamRequest request) {
        return ResponseEntity.status(201).body(BaseResponse.ok(teams.create(orgId, request)));
    }
}
