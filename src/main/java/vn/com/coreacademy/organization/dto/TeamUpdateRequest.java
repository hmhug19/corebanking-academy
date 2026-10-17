package vn.com.coreacademy.organization.dto;

import java.util.HashSet;
import java.util.Set;

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
public class TeamUpdateRequest {
    // Theo dõi field có trong JSON để phân biệt bỏ qua với null; không thay bằng setter Lombok.
    @JsonIgnore
    private final Set<String> suppliedFields = new HashSet<>();

    @Schema(description =
        "Mã nhóm. Bỏ qua để giữ nguyên; gửi null để bỏ mã. Mã có giá trị không được trùng trong tổ chức.")
    @Size(max = 50)
    private String code;

    @Schema(description = "Tên nhóm. Bỏ qua để giữ nguyên; không chấp nhận null hoặc chỉ có khoảng trắng.")
    @Size(min = 1, max = 255)
    private String name;

    @Schema(description = "Mô tả nhóm. Bỏ qua để giữ nguyên; gửi null để xóa mô tả.")
    @Size(max = 4000)
    private String description;

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

    @JsonSetter("description")
    public void setDescription(String value) {
        suppliedFields.add("description");
        description = value;
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
