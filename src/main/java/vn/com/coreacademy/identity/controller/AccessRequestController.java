package vn.com.coreacademy.identity.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
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
import vn.com.coreacademy.identity.dto.ResetPasswordResult;
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

    @PostMapping("/{requestId}/approve")
    @Operation(
        summary = "Duyệt yêu cầu đăng ký của khách",
        description = """
            Duyệt một yêu cầu đăng ký đang chờ trong phạm vi quản lý, kèm ghi chú không bắt buộc.
            - Tạo tài khoản với mật khẩu khách đã chọn (không phải đổi lại) và gán vai trò học viên, phạm vi OWN.
            - Yêu cầu ngoài phạm vi trả `CORE-RESOURCE_NOT_FOUND`; yêu cầu đã xử lý hoặc không phải đăng ký trả
              `CORE-STATE_CONFLICT`.
            - Mã cán bộ đã bị dùng trong lúc chờ trả `IDENTITY-DUPLICATE_IDENTIFIER` và yêu cầu vẫn ở trạng thái chờ.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<AccessRequestView>> approve(@PathVariable Long requestId,
        @Valid @RequestBody(required = false) RejectAccessRequest request) {
        return ResponseEntity.ok(BaseResponse.ok(requests.approve(requestId, request == null ? null : request.note())));
    }

    @PostMapping("/{requestId}/reset-password")
    @Operation(
        summary = "Cấp mật khẩu tạm cho yêu cầu quên mật khẩu",
        description = """
            Đặt lại mật khẩu của tài khoản mà yêu cầu đang chờ trỏ tới, kèm ghi chú không bắt buộc.
            - Trả mật khẩu tạm đúng một lần (không lưu, không ghi nhật ký); người dùng phải đổi khi đăng nhập.
            - Phiên cũ của tài khoản bị vô hiệu. Phản hồi không được lưu đệm (`Cache-Control: no-store`).
            - Yêu cầu ngoài phạm vi trả `CORE-RESOURCE_NOT_FOUND`; yêu cầu đã xử lý, không phải quên mật khẩu,
              không khớp tài khoản, hoặc tài khoản SSO, bị khóa, ngừng hoạt động trả `CORE-STATE_CONFLICT`.
            """
    )
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status để phân biệt thành công với lỗi nghiệp vụ.",
        useReturnTypeSchema = true)
    public ResponseEntity<BaseResponse<ResetPasswordResult>> resetPassword(@PathVariable Long requestId,
        @Valid @RequestBody(required = false) RejectAccessRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(BaseResponse.ok(requests.resetPassword(requestId, request == null ? null : request.note())));
    }
}
