package vn.com.coreacademy.common.web;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import vn.com.coreacademy.common.dto.BaseResponse;
import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class))),
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class))),
    @ApiResponse(responseCode = "403", description = "Access denied", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class))),
    @ApiResponse(responseCode = "500", description = "Server error; provide responseId for investigation", content = @Content(mediaType = "application/json", schema = @Schema(implementation = BaseResponse.class)))
})
public @interface StandardApiErrors {}
