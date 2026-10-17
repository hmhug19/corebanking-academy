package vn.com.coreacademy.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

public record CreateOrganizationRequest(
    @Schema(description = "UNIT: đơn vị cấp gốc hoặc dưới UNIT; DEPARTMENT: phòng, bắt buộc có cha UNIT.")
    @NotBlank @Pattern(regexp = "UNIT|DEPARTMENT") String orgType,
    @Schema(description = "Tổ chức cha phải là UNIT đang hoạt động; chỉ UNIT được bỏ trống parentId.")
    @Positive Long parentId,
    @NotBlank @Size(max = 50) String code,
    @NotBlank @Size(max = 255) String name,
    @PositiveOrZero int sortNo) {
}
