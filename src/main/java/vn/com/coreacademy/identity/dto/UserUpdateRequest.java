package vn.com.coreacademy.identity.dto;

import java.util.HashSet;
import java.util.Set;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vn.com.coreacademy.common.exception.UnsupportedFieldException;

@Getter
@NoArgsConstructor
public class UserUpdateRequest {
    // Theo dõi field có trong JSON để phân biệt bỏ qua với null; không thay bằng setter Lombok.
    @JsonIgnore
    private final Set<String> suppliedFields = new HashSet<>();

    @Schema(description = "Họ tên. Bỏ qua để giữ nguyên; không chấp nhận null hoặc chỉ có khoảng trắng.")
    @Size(min = 1, max = 255)
    private String fullName;

    @Schema(description = "Email. Bỏ qua để giữ nguyên; gửi null để xóa thông tin email.")
    @Email
    @Size(max = 100)
    private String email;

    @Schema(description = "Chức danh mô tả, không cấp quyền. Bỏ qua để giữ nguyên; gửi null để xóa.")
    @Size(max = 255)
    private String positionName;

    @Schema(description = "ID tổ chức. Bỏ qua để giữ nguyên; gửi null để bỏ gán, theo phạm vi quản lý.")
    @Positive
    private Long orgId;

    @Schema(description = "ID nhóm thuộc đúng orgId. Bỏ qua để giữ nguyên; gửi null để bỏ gán nhóm.")
    @Positive
    private Long teamId;

    @JsonSetter("fullName")
    public void setFullName(String value) {
        suppliedFields.add("fullName");
        fullName = value;
    }

    @JsonSetter("email")
    public void setEmail(String value) {
        suppliedFields.add("email");
        email = value;
    }

    @JsonSetter("positionName")
    public void setPositionName(String value) {
        suppliedFields.add("positionName");
        positionName = value;
    }

    @JsonSetter("orgId")
    public void setOrgId(Long value) {
        suppliedFields.add("orgId");
        orgId = value;
    }

    @JsonSetter("teamId")
    public void setTeamId(Long value) {
        suppliedFields.add("teamId");
        teamId = value;
    }

    @JsonIgnore
    public boolean has(String field) {
        return suppliedFields.contains(field);
    }

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new UnsupportedFieldException(field);
    }
}
