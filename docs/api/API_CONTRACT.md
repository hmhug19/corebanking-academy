# Quy chuẩn giao tiếp API với frontend

Áp dụng cho JSON API nghiệp vụ `/api/v1/**`. Request nhận DTO trực tiếp; response dùng BaseResponse, không bọc ngầm bằng ResponseBodyAdvice. Swagger/OpenAPI, Actuator, file/stream và CORS preflight giữ protocol gốc.

## Request

POST/PUT/PATCH nhận JSON trực tiếp, không có BaseRequest hoặc wrapper data. Login gửi `{"username":"super.admin","password":"<password>"}`. GET/DELETE dùng path/query; logout không cần body. PATCH phân biệt field bỏ qua và null; field ngoài whitelist bị từ chối. Không lấy actor/role/scope từ metadata client để phân quyền.

## Response

Quy tắc format tập trung tại [guideline chung](../../DEVELOPMENT_GUIDELINES.md#response-value-format). BaseResponse có các field camelCase; status là SUCCESS hoặc ERROR; mã theo PREFIX-REASON không chứa HTTP; message/messageTitle bằng tiếng Việt tự nhiên.

Ví dụ thành công:

```json
{
  "responseId": "fe-request-001",
  "timestamp": "2026-10-17T00:00:00Z",
  "status": "SUCCESS",
  "messageCode": "CORE-SUCCESS",
  "message": "Thao tác thành công.",
  "messageTitle": "Thành công",
  "data": null
}
```

- data luôn có khóa, null khi không có payload; khi thành công có kiểu DTO theo Swagger. Không trả entity hoặc credential/hash/token nội bộ trong lỗi.
- errors chỉ có khi có chi tiết lỗi trường, gồm field/code/message; không có rejectedValue. Field path có thể là `initialRoleGrants[0].roleId`. Field rỗng chỉ lỗi toàn request như EMPTY_UPDATE.
- Cùng messageCode dùng một message/title mặc định. Nếu có đúng một errors item, message ngoài bằng errors[0].message; nếu có nhiều lỗi hoặc không có chi tiết thì dùng message mặc định. messageTitle vẫn theo mã nghiệp vụ. FE không so sánh chuỗi message để phân loại lỗi.
- BaseResponse.ok tạo SUCCESS; BaseResponse.fail tạo ERROR, độc lập với HTTP status. ApiResponseCode.isSuccess phân biệt mã thành công; không suy từ HTTP 200 hoặc prefix chuỗi.

## Lỗi và HTTP status

| Loại kết quả | HTTP | status |
|---|---|---|
| Thành công | 200; tạo mới 201 | SUCCESS |
| Validation DTO/query/path, field ngoài whitelist, lỗi logic nghiệp vụ, xung đột hoặc dữ liệu không tìm thấy | 200 | ERROR |
| Sai thông tin đăng nhập hoặc phiên/token không hợp lệ | 401 | ERROR |
| Không có quyền hoặc chưa đổi mật khẩu tạm | 403 | ERROR |
| Body bắt buộc bị thiếu, JSON sai cú pháp | 400 | ERROR |
| URL API không tồn tại | 404 | ERROR |
| Method/Accept/Content-Type không hỗ trợ | 405/406/415 | ERROR |
| Range file sai/không hỗ trợ | 416 | ERROR |
| Vượt giới hạn request nếu được cấu hình | 429 | ERROR |
| Lỗi hệ thống ngoài dự kiến | 500 | ERROR |

HTTP 200 chỉ xác nhận xử lý request; FE bắt buộc kiểm tra status trước khi sử dụng data hoặc thông báo thành công. Dữ liệu không tồn tại và tài nguyên ngoài scope có cùng CORE-RESOURCE_NOT_FOUND để tránh dò dữ liệu. URL API không tồn tại dùng CORE-ENDPOINT_NOT_FOUND và HTTP 404.

Ví dụ validation trả HTTP 200:

```json
{
  "responseId": "fe-request-002",
  "timestamp": "2026-10-17T00:00:00Z",
  "status": "ERROR",
  "messageCode": "CORE-VALIDATION_FAILED",
  "message": "Thông tin không đúng định dạng.",
  "messageTitle": "Thông tin chưa hợp lệ",
  "data": null,
  "errors": [
    {"field": "email", "code": "INVALID_FORMAT", "message": "Thông tin không đúng định dạng."}
  ]
}
```

Annotation validation được chuyển sang mã chi tiết ổn định REQUIRED/INVALID_FORMAT/INVALID_LENGTH/OUT_OF_RANGE/INVALID_VALUE. Validation tại service bổ sung TOO_LONG, EMPTY_UPDATE, CONFLICTING_FIELDS, UNSUPPORTED_FIELD, PASSWORD_POLICY hoặc SAME_AS_CURRENT khi phù hợp. Mật khẩu mới có giới hạn 72 byte UTF-8; Size trên DTO kiểm tra độ dài String, PasswordPolicy kiểm tra giới hạn byte. Lỗi nghiệp vụ vẫn ném BusinessException để rollback transaction dù response HTTP là 200.

ApiExceptionHandler chuẩn hóa lỗi MVC/service; ApiSecurityErrorHandler xử lý lỗi Security với cùng envelope. HTTP 405 giữ Allow; lỗi token tại Security giữ WWW-Authenticate: Bearer. ResponseId hỗ trợ tra cứu lỗi hệ thống; không trả exception message/stack/SQL hoặc giá trị mật khẩu cho client. Không gom lỗi DB bất ngờ thành lỗi nghiệp vụ để che lỗi hệ thống.

## Chi tiết lỗi nghiệp vụ theo field

Giữ nguyên messageCode và HTTP 200/ERROR cho lỗi nghiệp vụ. Khi xác định được trường cần sửa, errors chứa field/code/message; xử lý tuần tự, không cam kết gom mọi lỗi nghiệp vụ trong một lượt. Không thêm API check trùng riêng. Backend vẫn kiểm tra khi lưu dù FE đã validate đầu vào.

| Case | messageCode | field | errors[].code |
|---|---|---|---|
| Mã cán bộ trùng mã cán bộ hoặc username đã có | IDENTITY-IDENTIFIER_EXISTS | userId | ALREADY_EXISTS |
| Username trùng username hoặc mã cán bộ đã có | IDENTITY-IDENTIFIER_EXISTS | username | ALREADY_EXISTS |
| Trùng mã tổ chức/phòng | ORGANIZATION-ORG_CODE_EXISTS | code | ALREADY_EXISTS |
| Trùng mã nhóm trong cùng tổ chức | ORGANIZATION-TEAM_CODE_EXISTS | code | ALREADY_EXISTS |
| Thiếu tổ chức khi gán nhóm | IDENTITY-MEMBERSHIP_INVALID | orgId | REQUIRED |
| Tổ chức không còn tồn tại | IDENTITY-MEMBERSHIP_INVALID | orgId hoặc field theo ngữ cảnh | NOT_FOUND |
| Tổ chức/cấp cha ngừng hoạt động hoặc cây không hợp lệ | IDENTITY-MEMBERSHIP_INVALID | orgId hoặc field theo ngữ cảnh | INVALID_VALUE |
| Nhóm không tồn tại/ngừng hoạt động/khác tổ chức | IDENTITY-MEMBERSHIP_INVALID hoặc IDENTITY-SCOPE_INVALID | teamId | NOT_FOUND / INACTIVE / MISMATCH |
| Vai trò không tồn tại hoặc ngừng hoạt động | IDENTITY-SCOPE_INVALID | roleId | INVALID_VALUE |
| Thiếu target theo scope | IDENTITY-SCOPE_INVALID | orgId / teamId | REQUIRED |
| Gửi target không được phép cho scope | IDENTITY-SCOPE_INVALID | orgId / teamId | NOT_ALLOWED |
| Phân quyền role/scope/target đã tồn tại | IDENTITY-ROLE_GRANT_EXISTS | roleId | ALREADY_EXISTS |

Lỗi trong initialRoleGrants dùng đường dẫn có chỉ số, ví dụ initialRoleGrants[0].roleId. Tổ chức cha khi tạo node dùng parentId; khi chọn TEAM không gửi orgId, lỗi tổ chức của nhóm gắn vào teamId. Không thay lỗi thiếu quyền thành lỗi field hoặc phân biệt dữ liệu ngoài scope để dò tài nguyên. Lỗi xóa nhóm đang được sử dụng, bảo vệ SUPER_ADMIN cuối cùng và đăng nhập sai vẫn giữ thông báo toàn thao tác.

Ví dụ trùng username:

```json
{
  "responseId": "fe-request-003",
  "timestamp": "2026-10-17T00:00:00Z",
  "status": "ERROR",
  "messageCode": "IDENTITY-IDENTIFIER_EXISTS",
  "message": "Tên đăng nhập đã được sử dụng làm tên đăng nhập hoặc mã cán bộ.",
  "messageTitle": "Trùng thông tin tài khoản",
  "data": null,
  "errors": [
    {
      "field": "username",
      "code": "ALREADY_EXISTS",
      "message": "Tên đăng nhập đã được sử dụng làm tên đăng nhập hoặc mã cán bộ."
    }
  ]
}
```

## Danh mục messageCode

CORE đặt trong common.exception.ResponseCode; module có enum riêng implements ApiResponseCode. IdentityResponseCode quản lý tài khoản/phân quyền; OrganizationResponseCode quản lý org/team. Không thêm HTTP vào mã và không nhân bản mã cho từng field.

| messageCode | HTTP | Thông báo |
|---|---:|---|
| CORE-SUCCESS | 200 | Thao tác thành công. |
| CORE-VALIDATION_FAILED | 200 | Dữ liệu đầu vào không hợp lệ. |
| CORE-MALFORMED_REQUEST | 400 | Nội dung yêu cầu bị thiếu hoặc JSON không đúng cú pháp. |
| CORE-BAD_REQUEST | 400 | Yêu cầu không hợp lệ. |
| CORE-UNAUTHENTICATED | 401 | Phiên đăng nhập không hợp lệ hoặc đã hết hiệu lực. Vui lòng đăng nhập lại. |
| CORE-ACCESS_DENIED | 403 | Bạn không có quyền thực hiện thao tác này. |
| CORE-ENDPOINT_NOT_FOUND | 404 | Không tìm thấy tài nguyên yêu cầu. |
| CORE-RESOURCE_NOT_FOUND | 200 | Không tìm thấy dữ liệu yêu cầu. |
| CORE-METHOD_NOT_ALLOWED | 405 | Phương thức HTTP không được hỗ trợ. |
| CORE-NOT_ACCEPTABLE | 406 | Định dạng phản hồi yêu cầu không được hỗ trợ. |
| CORE-STATE_CONFLICT | 200 | Thao tác không phù hợp với trạng thái dữ liệu hiện tại. |
| CORE-UNSUPPORTED_MEDIA_TYPE | 415 | Kiểu nội dung yêu cầu không được hỗ trợ. |
| CORE-RANGE_NOT_SATISFIABLE | 416 | Khoảng byte yêu cầu không hợp lệ hoặc không được hỗ trợ. |
| CORE-TOO_MANY_REQUESTS | 429 | Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau. |
| CORE-INTERNAL_ERROR | 500 | Hệ thống gặp lỗi. Vui lòng thử lại sau hoặc cung cấp responseId để được hỗ trợ. |
| IDENTITY-CREDENTIALS_INVALID | 401 | Tên đăng nhập hoặc mật khẩu không đúng. |
| IDENTITY-PASSWORD_CHANGE_REQUIRED | 403 | Vui lòng đổi mật khẩu tạm trước khi tiếp tục. |
| IDENTITY-CURRENT_PASSWORD_INCORRECT | 200 | Mật khẩu hiện tại không đúng. |
| IDENTITY-IDENTIFIER_EXISTS | 200 | Mã cán bộ hoặc tên đăng nhập đã được sử dụng. |
| IDENTITY-MEMBERSHIP_INVALID | 200 | Tổ chức hoặc nhóm không hợp lệ, ngừng hoạt động hoặc không khớp nhau. |
| IDENTITY-LAST_SUPER_ADMIN | 200 | Hệ thống phải còn ít nhất một SUPER_ADMIN đang hoạt động với phạm vi GLOBAL. |
| IDENTITY-ROLE_GRANT_EXISTS | 200 | Phân quyền cho vai trò và phạm vi này đang tồn tại. |
| IDENTITY-SCOPE_INVALID | 200 | Vai trò hoặc phạm vi phân quyền không hợp lệ. |
| ORGANIZATION-ORG_CODE_EXISTS | 200 | Mã tổ chức đã được sử dụng. |
| ORGANIZATION-TEAM_CODE_EXISTS | 200 | Mã nhóm đã được sử dụng trong tổ chức này. |
| ORGANIZATION-TEAM_IN_USE | 200 | Không thể xóa nhóm còn thành viên hoặc phân quyền tham chiếu. |
| ORGANIZATION-HIERARCHY_INVALID | 200 | Phòng phải trực thuộc đơn vị; tổ chức cha không được là phòng. |

Thông tin đăng nhập không hợp lệ (không tồn tại/sai mật khẩu/khóa/ngừng hoạt động/SSO dùng mật khẩu local) dùng cùng IDENTITY-CREDENTIALS_INVALID, HTTP 401. Chỉ đổi mật khẩu của user đã xác thực mới phân biệt CURRENT_PASSWORD_INCORRECT với lỗi mật khẩu mới. Chi tiết tích hợp tại [Identity](IDENTITY.md).

## Request correlation và cache

FE có thể gửi X-Request-Id dài 1–64 ký tự: bắt đầu bằng chữ/số, còn lại chữ/số/dấu chấm/gạch dưới/gạch ngang. Thiếu hoặc sai được thay bằng UUID. ResponseId khớp X-Request-Id; timestamp UTC. ID không dùng làm authorization hoặc idempotency key.

RequestIdFilter chạy trước Security, giữ ID qua dispatch, đặt header/MDC và khôi phục trong finally. CORS cho phép FE gửi/đọc header. Không log body/Authorization; async/executor cần truyền context có chủ đích. SecurityConfig quản lý cache chung no-cache/no-store; controller không lặp header này.

## Tích hợp FE và Swagger

- Login lấy token từ data.accessToken chỉ khi status=SUCCESS; /me đọc data.user/roles/functions/roleGrants/functionDetails/functionScopes.
- HTTP interceptor cần xử lý cả nhánh HTTP 200 với status=ERROR. Hiển thị lỗi trường từ errors; xử lý nghiệp vụ theo messageCode. Login 401 hiển thị lỗi form; token 401 xóa phiên; 403 phân nhánh IDENTITY-PASSWORD_CHANGE_REQUIRED để chuyển sang đổi mật khẩu; CORE-ACCESS_DENIED báo không có quyền, tránh retry lặp.
- Tạo mới thành công vẫn trả 201; tạo mới thất bại nghiệp vụ trả 200/ERROR, không có resource/Location thành công. Logout thành công trả 200/SUCCESS/data=null; FE cũng xóa phiên local khi token bị từ chối 401.
- Swagger mô tả 200/SUCCESS và 200/ERROR; endpoint tạo mới có cả 201 và 200. StandardApiErrors giữ lỗi giao thức/bảo mật chung. Schema data của response thành công có thể nullable trong lỗi.
- Không tự retry thao tác ghi chỉ vì timeout hoặc có responseId. Monitoring cần đếm status/messageCode ngoài HTTP status để nhận diện lỗi nghiệp vụ.

Thay đổi contract: mã cũ có HTTP số đã được thay bằng mã semantic; message/title chuyển tiếng Việt và validation/lỗi nghiệp vụ chuyển sang HTTP 200. FE phải cập nhật đồng bộ, không tiếp tục kiểm tra mã thành công cũ hoặc chờ HTTP 400/409 cho lỗi logic.

## Kiểm chứng

Maven clean verify kiểm tra build và test thực sự tồn tại; repository hiện có test Knowledge; kiểm tra số test thực sự chạy và test bị skip theo môi trường. Kiểm tra HTTP riêng cần xác minh SUCCESS/ERROR độc lập HTTP, field errors, lỗi login/token/quyền, lỗi giao thức và rollback. Smoke check với H2 không thay kiểm tra PostgreSQL, advisory lock nhiều instance hoặc Docker.


## Postman

Import [collection 21 API](../postman/corebanking-academy.postman_collection.json) và environment [Local](../postman/local.postman_environment.json) hoặc [Dev](../postman/dev.postman_environment.json). Local dùng http://localhost:8019; Dev dùng http://103.121.90.119:8019.

Chọn environment, điền username/password và gửi Login. accessToken tự lưu theo environment, các request khác kế thừa Bearer; login dùng No Auth và xóa token cũ trước khi gửi. Me lưu mustChangePassword; nếu true, đổi mật khẩu riêng, cập nhật password rồi login lại. Đổi mật khẩu/logout thành công sẽ xóa token đã thu hồi trong environment.

GET roles tự lưu ID vai trò theo code. Tạo org/team/user/grant thành công tự lưu orgId/teamId/targetUserId/grantId; các request list không tự chọn tài nguyên để sửa. Điền initialPassword khi tạo user; newUserId/newUsername có thể để trống để tự sinh. Filter tùy chọn mặc định disabled. Đây là bộ request thử thủ công/chạy từng folder, không phải kịch bản chạy toàn collection và cleanup dữ liệu; DELETE team chỉ thành công khi không còn tham chiếu. Script test kiểm tra cả HTTP lẫn status/messageCode để nhận diện HTTP 200/ERROR.

File export không có mật khẩu/token thật. Chỉ giữ chúng trong environment cá nhân khi chạy; không commit file đã export lại có credential. Dùng description của từng request để xem field, phạm vi và điều kiện thực hiện.
