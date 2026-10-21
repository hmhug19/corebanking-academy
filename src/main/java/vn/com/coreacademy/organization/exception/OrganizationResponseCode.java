package vn.com.coreacademy.organization.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import vn.com.coreacademy.common.exception.ApiResponseCode;

@Getter
@RequiredArgsConstructor
public enum OrganizationResponseCode implements ApiResponseCode {
    ORG_CODE_EXISTS(HttpStatus.OK, "ORGANIZATION-ORG_CODE_EXISTS",
        "Mã tổ chức đã được sử dụng.", "Trùng mã tổ chức"),
    TEAM_CODE_EXISTS(HttpStatus.OK, "ORGANIZATION-TEAM_CODE_EXISTS",
        "Mã nhóm đã được sử dụng trong tổ chức này.", "Trùng mã nhóm"),
    TEAM_IN_USE(HttpStatus.OK, "ORGANIZATION-TEAM_IN_USE",
        "Không thể xóa nhóm còn thành viên, phân quyền hoặc dữ liệu học tập tham chiếu.", "Nhóm đang được sử dụng"),
    HIERARCHY_INVALID(HttpStatus.OK, "ORGANIZATION-HIERARCHY_INVALID",
        "Phòng phải trực thuộc đơn vị; tổ chức cha không được là phòng.", "Cấu trúc tổ chức chưa hợp lệ");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
    private final String title;
}
