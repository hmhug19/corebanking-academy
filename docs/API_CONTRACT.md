# Quy chuẩn giao tiếp API với frontend

Áp dụng cho JSON API nghiệp vụ `/api/v1/**`. Controller nhận DTO request trực tiếp và trả BaseResponse; không có ResponseBodyAdvice bọc ngầm. Swagger/OpenAPI, Actuator health, tải file, streaming và CORS preflight giữ protocol gốc. API mới tuân theo hướng dẫn này; API hiện có đã chuyển là login/me/logout.

## Request

POST/PUT/PATCH có JSON body nhận DTO nghiệp vụ trực tiếp. Ví dụ login:

```json
{"username":"hunghm8","password":"<PASSWORD>"}
```

Controller nhận `@Valid @RequestBody LoginRequest request`, gọi `service.login(request)`. Field validation nằm ngay trên DTO; lỗi trả field `username` hoặc `password`, không có prefix data.

Không dùng lớp base hoặc wrapper chung cho request khi chưa có nhu cầu nghiệp vụ. Mỗi API có request DTO riêng; chỉ response có envelope chung. GET/DELETE dùng path/query; logout không cần body. DTO nghiệp vụ tiếp tục dùng record. Khi payload có nested DTO thực sự, dùng @Valid tại trường nested để validation cascade.

Request ID ở header X-Request-Id. UserId/role/scope của người đang gọi lấy từ authentication và dữ liệu server, không tin metadata phía client để phân quyền. Body cũ bọc `{data: ...}` không được tự unwrap; FE gửi các trường trực tiếp.

## Response

Envelope bất biến, generic `BaseResponse<T>`, tên JSON camelCase:

```json
{
  "responseId": "fe-request-001",
  "timestamp": "2026-10-16T07:00:00Z",
  "status": "SUCCESS",
  "messageCode": "CORE-0000",
  "message": "Request completed successfully",
  "messageTitle": "Success",
  "data": {
    "user": {
      "userId": "183617",
      "username": "hunghm8"
    },
    "roles": [],
    "functions": [],
    "roleGrants": [],
    "functionDetails": [],
    "functionScopes": {}
  }
}
```

- responseId khớp header `X-Request-Id`; timestamp UTC do backend tạo.
- status: SUCCESS hoặc ERROR. messageCode là mã ổn định để FE xử lý; message/messageTitle phục vụ hiển thị, FE có thể dùng messageCode để dịch.
- data có đúng kiểu DTO trong Swagger và Java; luôn có khóa, null khi không có payload. errors chỉ xuất hiện khi có lỗi trường, không chứa rejectedValue.
- Không dùng @NotBlank cho T data; không cần Java Serializable cho JSON HTTP. Không thêm thư viện NanoID: UUID đáp ứng correlation, không dùng làm chứng thực hoặc idempotency key.
- `BaseResponse.ok(data)` và `BaseResponse.ok()` tạo response thành công; HTTP status đặt ở ResponseEntity. Tạo mới resource có thể dùng 201 với Location và BaseResponse.ok(data).

## Lỗi và HTTP status

HTTP status luôn phản ánh kết quả. Không biến 400/401/403/500 thành HTTP 200. Ví dụ validation HTTP 400:

```json
{
  "responseId": "fe-request-002",
  "timestamp": "2026-10-16T07:00:00Z",
  "status": "ERROR",
  "messageCode": "CORE-0400-VALIDATION",
  "message": "Request validation failed",
  "messageTitle": "Invalid request",
  "data": null,
  "errors": [
    {
      "field": "username",
      "code": "NotBlank",
      "message": "must not be blank"
    }
  ]
}
```

Mã chung trong common.exception.ResponseCode. Module có thể tạo enum riêng implements ApiResponseCode để quản lý mã nghiệp vụ, không thêm tất cả mã vào common. Dùng BusinessException(code) với code có HTTP error status; không truyền exception message tự do ra client. Nếu API có mã lỗi nghiệp vụ riêng, khai báo thêm schema/response code trong Swagger.

ApiExceptionHandler chuẩn hóa validation, JSON thiếu/sai, 404, 405, 406, 415, exception nghiệp vụ và lỗi server. 405 giữ Allow header. Spring Security dùng ApiSecurityErrorHandler cho token thiếu/sai/hết hạn, 401 và 403; 401 có WWW-Authenticate: Bearer. Lỗi 500 chỉ trả thông báo chung, log có responseId để điều tra. Không log request body/header bí mật hoặc echo password/JWT/exception stack ra response.

CORS từ origin bị chặn và lỗi xảy ra trước khi request tới ứng dụng (proxy/container) thuộc protocol hạ tầng, không bảo đảm envelope. Không áp dụng body envelope cho 204, HEAD hoặc file/stream; với endpoint cần xác nhận JSON như logout, dùng 200 thay 204.

## Request correlation

FE có thể gửi X-Request-Id dài 1–64 ký tự, bắt đầu bằng chữ/số; còn lại chỉ chữ/số/dấu chấm/gạch dưới/gạch ngang. Giá trị thiếu hoặc không hợp lệ được thay bằng UUID; không dùng giá trị này cho authorization.

RequestIdFilter đặt header và MDC trước Spring Security, giữ ID qua servlet dispatch và khôi phục MDC trong finally để không lẫn request khác. Log4j2 pattern có requestId. CORS cho phép FE gửi và đọc header này. ID do client gửi không bảo đảm duy nhất; nếu cần truy vết phân tán phải thiết kế trace context riêng. Công việc chạy trên executor/@Async cần truyền context riêng, không tự kế thừa MDC.

## Cách viết controller mới

```java
@StandardApiErrors
@RestController
@RequestMapping("/api/v1/example")
class ExampleController {
    @PostMapping
    ResponseEntity<BaseResponse<ExampleInfo>> create(@Valid @RequestBody CreateExampleRequest request) {
        ExampleInfo result = service.create(request);
        return ResponseEntity.ok(BaseResponse.ok(result));
    }
}
```

Không trả BaseResponse từ repository/entity hoặc nhét HTTP handling vào service. Không bọc BaseResponse lần hai. StandardApiErrors cung cấp lỗi Swagger chung; khai báo thêm khi endpoint có mã HTTP khác như 409/422. Tránh trả String thô cho API nghiệp vụ.

## FE cần cập nhật

- Login gửi `{username, password}`; lấy JWT từ `response.data.accessToken`.
- Me đọc `response.data.user/roles/functions/roleGrants/functionDetails/functionScopes`.
- Logout nhận HTTP 200 với data=null, thu hồi mọi JWT cũ như trước. FE xóa token và cache; nếu nhận 401 vẫn xóa token local.
- HTTP interceptor xử lý theo HTTP status và messageCode, giữ responseId để báo lỗi. Không tự động retry thao tác ghi chỉ vì timeout hoặc có request ID.

## Kiểm tra

`mvn clean verify`: test MVC/Security kiểm tra envelope response, validation request trực tiếp, request ID/MDC cleanup, CORS, HTTP errors và business errors; mock service để không phụ thuộc database. Kiểm tra login/me/logout thật là bước riêng khi có môi trường và tài khoản được phép.
