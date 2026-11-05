package vn.com.coreacademy.identity.dto;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import vn.com.coreacademy.identity.constants.AccessRequestLimits;

public record RejectAccessRequest(
    @Schema(description = "Ghi chú từ chối, không bắt buộc, tối đa 500 ký tự.")
    @Size(max = AccessRequestLimits.NOTE_MAX) String note) {
}
