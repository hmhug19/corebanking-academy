package vn.com.coreacademy.organization.dto;

import java.util.HashSet;
import java.util.Set;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
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
public class OrganizationUpdateRequest {
    // Theo dõi field có trong JSON để phân biệt bỏ qua với null; không thay bằng setter Lombok.
    @JsonIgnore
    private final Set<String> suppliedFields = new HashSet<>();

    @Schema(description = "Mã tổ chức. Bỏ qua để giữ nguyên; không chấp nhận null hoặc chỉ có khoảng trắng.")
    @Size(min = 1, max = 50)
    private String code;

    @Schema(description = "Tên tổ chức. Bỏ qua để giữ nguyên; không chấp nhận null hoặc chỉ có khoảng trắng.")
    @Size(min = 1, max = 255)
    private String name;

    @Schema(description = "Thứ tự hiển thị. Bỏ qua để giữ nguyên; không chấp nhận null.")
    @PositiveOrZero
    private Integer sortNo;

    @Schema(description = "Trạng thái tổ chức. Bỏ qua để giữ nguyên; chỉ quản trị viên toàn hệ thống được đổi.")
    @Pattern(regexp = "active|inactive")
    private String status;

    @JsonSetter("code")
    public void setCode(String value) {
        suppliedFields.add("code");
        code = value;
    }

    @JsonSetter("name")
    public void setName(String value) {
        suppliedFields.add("name");
        name = value;
    }

    @JsonSetter("sortNo")
    public void setSortNo(Integer value) {
        suppliedFields.add("sortNo");
        sortNo = value;
    }

    @JsonSetter("status")
    public void setStatus(String value) {
        suppliedFields.add("status");
        status = value;
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
