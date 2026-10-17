package vn.com.coreacademy.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;

public record UserStatusRequest(
    @Schema(description = "active: hoạt động; inactive: tạm dừng; locked: khóa.")
    @NotBlank @Pattern(regexp = "active|inactive|locked") String status) {
}
