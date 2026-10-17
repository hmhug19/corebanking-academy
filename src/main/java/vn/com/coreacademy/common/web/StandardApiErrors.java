package vn.com.coreacademy.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import vn.com.coreacademy.common.dto.BaseResponse;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponses({
    @ApiResponse(responseCode = "400",
        description = "Nội dung yêu cầu bị thiếu hoặc JSON không đúng cú pháp.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class))),
    @ApiResponse(responseCode = "401",
        description = "Xác thực không thành công, thiếu token hoặc token không còn hiệu lực.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class))),
    @ApiResponse(responseCode = "403",
        description = "Không có quyền thực hiện thao tác hoặc truy cập dữ liệu trong phạm vi yêu cầu.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class))),
    @ApiResponse(responseCode = "500",
        description = "Lỗi hệ thống; cung cấp responseId để hỗ trợ tra cứu.",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
})
public @interface StandardApiErrors {
}
