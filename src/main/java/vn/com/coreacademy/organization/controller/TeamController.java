package vn.com.coreacademy.organization.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.organization.dto.TeamInfo;
import vn.com.coreacademy.organization.dto.TeamUpdateRequest;
import vn.com.coreacademy.organization.service.TeamAdministrationService;

@RestController
@RequestMapping(value = "/api/v1/teams", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Teams", description = "Quản lý thông tin và xóa nhóm chuyên môn thuộc đơn vị hoặc phòng.")
@StandardApiErrors
@RequiredArgsConstructor
public class TeamController {
    private final TeamAdministrationService teams;

    @PatchMapping("/{teamId}")
    @Operation(
        summary = "Cập nhật thông tin nhóm chuyên môn",
        description = """
            Cập nhật thông tin nhóm thuộc tổ chức đang hoạt động trong phạm vi quản lý.
            - Không chuyển nhóm sang tổ chức khác hoặc thay đổi trạng thái nhóm qua API này.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<TeamInfo>> update(@PathVariable Long teamId,
        @Valid @RequestBody TeamUpdateRequest request) {
        return ResponseEntity.ok(BaseResponse.ok(teams.update(teamId, request)));
    }

    @DeleteMapping("/{teamId}")
    @Operation(
        summary = "Xóa nhóm chưa có dữ liệu tham chiếu",
        description = """
            Xóa nhóm không còn thành viên, phân quyền hoặc dữ liệu học tập tham chiếu.
            - Tổ chức của nhóm phải đang hoạt động và thuộc phạm vi quản lý.
            - Còn tham chiếu trả mã `ORGANIZATION-TEAM_IN_USE`, kể cả phân quyền đã thu hồi hoặc lịch sử học tập.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<Void>> delete(@PathVariable Long teamId) {
        teams.delete(teamId);
        return ResponseEntity.ok(BaseResponse.ok());
    }
}
