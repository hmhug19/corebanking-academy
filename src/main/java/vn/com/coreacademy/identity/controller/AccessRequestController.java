package vn.com.coreacademy.identity.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.identity.dto.AccessRequestView;
import vn.com.coreacademy.identity.dto.RejectAccessRequest;
import vn.com.coreacademy.identity.service.AccessRequestService;

@RestController
@RequestMapping(value = "/api/v1/access-requests", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Access requests",
    description = "Yêu cầu đăng ký tài khoản và đặt lại mật khẩu của khách, chờ quản trị viên xử lý.")
@StandardApiErrors
@RequiredArgsConstructor
public class AccessRequestController {
    private final AccessRequestService requests;

    @GetMapping
    @Parameters({
        @Parameter(name = "kind", in = ParameterIn.QUERY, description = "REGISTER hoặc PASSWORD_RESET."),
        @Parameter(name = "status", in = ParameterIn.QUERY, description = "PENDING, APPROVED hoặc REJECTED."),
        @Parameter(name = "q", in = ParameterIn.QUERY,
            description = "Tìm theo mã cán bộ, họ tên hoặc email; tối đa 100 ký tự."),
        @Parameter(name = "page", in = ParameterIn.QUERY, description = "Chỉ số trang, bắt đầu từ 0."),
        @Parameter(name = "size", in = ParameterIn.QUERY,
            description = "Số bản ghi mỗi trang, từ 1 đến 100; mặc định 20."),
        @Parameter(name = "sort", in = ParameterIn.QUERY,
            description = "Dạng field,asc hoặc field,desc; field: id, createTime, staffCode.")
    })
    @Operation(
        summary = "Tra cứu yêu cầu của khách",
        description = """
            Danh sách yêu cầu đăng ký và đặt lại mật khẩu trong phạm vi quản lý của người gọi.
            - Quản trị toàn hệ thống thấy mọi yêu cầu; quản trị đơn vị chỉ thấy yêu cầu thuộc đơn vị của mình.
            - Yêu cầu không có đơn vị chỉ quản trị toàn hệ thống thấy.
            - Không trả mật khẩu hay mã băm của mật khẩu.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<PageResponse<AccessRequestView>>> list(
        @RequestParam(required = false) String kind, @RequestParam(required = false) String status,
        @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "createTime,desc") String sort) {
        return ResponseEntity.ok(BaseResponse.ok(requests.list(kind, status, q, page, size, sort)));
    }

    @PostMapping("/{requestId}/reject")
    @Operation(
        summary = "Từ chối yêu cầu của khách",
        description = """
            Từ chối một yêu cầu đang chờ trong phạm vi quản lý, kèm ghi chú không bắt buộc.
            - Yêu cầu ngoài phạm vi trả `CORE-RESOURCE_NOT_FOUND` để không lộ sự tồn tại.
            - Yêu cầu đã xử lý trả `CORE-STATE_CONFLICT`.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<AccessRequestView>> reject(@PathVariable Long requestId,
        @Valid @RequestBody(required = false) RejectAccessRequest request) {
        return ResponseEntity.ok(BaseResponse.ok(requests.reject(requestId, request == null ? null : request.note())));
    }
}
