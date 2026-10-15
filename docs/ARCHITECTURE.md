# Quy ước kiến trúc CORE Academy

Baseline: JDK 21 LTS, Spring Boot 3.5.16 GA. Maven parent quản lý version các thư viện; compile target và Docker build/runtime cùng dùng Java 21.

## Cấu trúc thống nhất

Ứng dụng là modular monolith: một Spring Boot application, tổ chức theo chức năng ở cấp đầu tiên, theo trách nhiệm kỹ thuật bên trong từng module. `identity` là module mẫu; organization và audit có mapping persistence. Learning và các module khác chưa có implementation, sẽ dùng cùng cấu trúc.

```text
vn.com.coreacademy
├── CoreAcademyApplication
├── config                         # Cấu hình hạ tầng toàn ứng dụng
│   └── CorsConfig
├── common                         # Thành phần dùng chung, không thuộc nghiệp vụ
│   ├── dto
│   │   └── ApiError
│   └── exception
│       └── ApiExceptionHandler
│   # common/persistence: CreatedEntity và UpdatedEntity dùng timestamp do DB sinh
├── identity
│   ├── controller                 # HTTP endpoints
│   │   └── AuthController
│   ├── service                    # Use case và transaction
│   │   ├── AuthService
│   │   ├── AuthorizationService
│   │   └── TokenService
│   ├── repository                 # Truy cập dữ liệu
│   ├── entity                     # JPA entities
│   ├── dto                        # Request/response
│   ├── enums                      # UserStatus, ScopeType
│   ├── converter                  # UserStatusConverter lưu status chữ thường
│   ├── constants                  # SecurityConstants
│   ├── security                   # Adapter Spring Security và current user
│   ├── config                     # IdentitySecurityConfig, JwtProperties
│   └── bootstrap                  # Khởi tạo admin có cấu hình
├── organization                   # entity/repository tổ chức, nhóm, chức danh
├── audit                          # entity/repository audit_log
├── learning                       # Chưa có implementation
├── assessment
├── content
├── progress
├── reporting
└── ...
```

## Trách nhiệm và phụ thuộc

| Package | Trách nhiệm | Quy tắc |
|---|---|---|
| controller | Nhận HTTP, validate request, lấy caller, gọi service, chọn HTTP status | Không gọi repository, không viết nghiệp vụ, không trả entity |
| service | Use case, kiểm tra quyền, phối hợp repository, dựng DTO | Đặt transaction ở use case; không phụ thuộc controller |
| repository | JPA query và persistence | Không xử lý HTTP hay quyết định quyền của caller |
| entity | Mapping database và trạng thái đối tượng | Field private; không dùng làm request/response |
| dto | Hợp đồng request/response | Dùng record khi phù hợp; không trả password hash |
| enums | Tập giá trị đóng như trạng thái, loại scope | JPA lưu STRING, tránh phụ thuộc thứ tự enum |
| constants | Giá trị cố định có tên thuộc module | Không gom mọi hằng số vào GlobalConstants |
| security | Adapter xác thực, chuyển JWT thành principal, lấy current user | Là Spring bean khi có dependency; không đặt vào utils |
| config | Wiring bean và binding cấu hình | Cấu hình riêng nằm trong module, hạ tầng chung nằm ở root config |
| bootstrap | Khởi tạo dữ liệu kỹ thuật có cấu hình | Không seed dữ liệu thật hoặc secrets cố định |

Module khác dùng service/DTO hoặc adapter công khai của module; kiểm tra quyền qua `identity.service.AuthorizationService`. Mapping quan hệ persistence có thể tham chiếu entity ở module khác bằng FK; use case không truy vấn repository của module khác trực tiếp. `common` không import module nghiệp vụ. Chưa có công cụ cưỡng chế ranh giới module tự động.

Service dùng class cụ thể và constructor injection. Chỉ thêm interface khi có nhiều implementation hoặc cần contract thay thế; không tạo cặp Service/ServiceImpl cho mọi class.

Entity/DTO được mapping trong `<module>.mapper` bằng MapStruct. `identity.mapper.UserMapper` là mẫu; service inject mapper thay vì tự dựng UserInfo. Cấu hình chung nằm trong `common.mapper.CentralMapperConfig`: Spring component, constructor injection, thiếu target field hoặc chuyển kiểu mất dữ liệu sẽ lỗi compile. Converter trạng thái cần giữ giá trị `active/inactive/locked`. Exception nghiệp vụ riêng đặt trong `<module>.exception`.

## Common, utils và constants

- `common.dto`, `common.exception`: hợp đồng lỗi và xử lý lỗi chung, hiện đã có code.
- `common.util`: chỉ thêm khi có hàm thuần dùng chung nhiều module, ví dụ xử lý chuỗi/thời gian. Không chứa service, phân quyền, truy vấn database hoặc truy cập Spring context. Hiện chưa có utility cần dùng chung nên chưa tạo lớp rỗng.
- `<module>.util`: helper riêng của module, nếu cần.
- `<module>.constants`: hằng số thuộc module, hiện có SecurityConstants.
- `common.constants`: chỉ dùng khi hằng số có cùng ý nghĩa ở nhiều module và không thuộc module cụ thể; hiện chưa cần.
- Giá trị thay đổi theo môi trường phải ở configuration properties. `JwtProperties` bind `app.jwt`, kiểm tra expiry và che signing secret trong `toString()`.

## Luồng mẫu

```text
POST /api/v1/auth/login
  → AuthController → AuthService
  → AuthenticationManager / DaoAuthenticationProvider
  → LocalUserDetailsService → LoginUserResolver → UserRepository
  → UserAccount chứa password_hash; chỉ auth_provider=LOCAL được login mật khẩu
  → PasswordEncoder kiểm tra mật khẩu và trạng thái
  → TokenService tạo JWT HS256

GET /api/v1/auth/me
  → SecurityFilterChain / JwtDecoder
  → UserJwtAuthenticationConverter kiểm tra trạng thái và auth version
  → AuthController / CurrentUser
  → AuthService → AuthorizationService → MeResponse

```

JWT subject là mã cán bộ dạng chuỗi; auth version map cột auth_ver. Quyền đọc từ database, cùng assignment với scope. GLOBAL/ORG/TEAM/OWN được kiểm tra theo resource context; không tự kế thừa xuống tổ chức con. Backend phải xác định org/team/owner từ dữ liệu thật. Learning API chưa triển khai.

Giữ URL login/me và trường request username; username nhận cả mã cán bộ và username, normalize username thành chữ thường. JWT principal luôn dùng mã cán bộ. Từ chối login nếu không có local credentials hoặc định danh khớp nhiều user. Response /me.user.userId là chuỗi, status chữ thường; /me trả user, roles, functions, roleGrants, functionDetails và functionScopes; scope chứa orgId/teamId, không còn id/UNIT. Expiry lấy từ app.jwt.expiry-minutes. Lỗi đăng nhập trả 401.

## Database và script mới

Source mapping 8 bảng của docs/sql/learning_hub_identity.sql. UserAccount chứa password_hash (JsonIgnore), auth_provider, org_id, team_id và position_name. UserStatusConverter lưu active/inactive/locked. RoleAssignment giữ role và scope riêng cho từng grant. Function và RoleFunction quản lý màn hình FE; không thay thế kiểm tra hành động API.

Không sinh FOREIGN KEY vật lý. Các quan hệ là logic, API quản trị khi triển khai phải kiểm tra user/org/team tồn tại, team thuộc org, cây đơn vị không có vòng lặp và quyền của người gán. TEAM grant cho phép org_id NULL; lúc kiểm tra, service đọc team để xác nhận org thực tế. Không tự suy BRN/BRCD/CRA thành cây đơn vị.

create_time dùng default PostgreSQL; update_time được cập nhật bởi JPA lifecycle do SQL compact không có trigger. Role và Function không có timestamp; Team chỉ có create_time; RoleFunction dùng created_at. AuditLog.details dùng JSONB.

Bootstrap cần APP_BOOTSTRAP_ADMIN_ID/USER/PASSWORD, tạo user có hash và GLOBAL ADMIN trong cùng transaction; không reset tài khoản có sẵn. SUPER_ADMIN được gán tường minh; không tự cấp cho tài khoản đang login.

`ddl-auto=validate` tránh tự thay đổi schema. Flyway trong cấu hình hiện tại đang tắt; cơ chế khởi tạo database cần được chọn khi triển khai.

## Kiểm thử

Test package phản chiếu module và trách nhiệm. Test đăng nhập dùng Spring MVC/filter, BCrypt/JWT thật và mock repository. Kiểm tra login theo hai định danh, giữ số 0 đầu, thiếu credentials, định danh mơ hồ, trạng thái tài khoản và thu hồi JWT. Test authorization kiểm tra scope ORG/TEAM/OWN và không ghép nhầm assignment. Test mapping dựng Hibernate metadata/session factory không kết nối DB, đối chiếu đủ 8 bảng, column/type/length và primary key với SQL.

Sau khi đổi package chạy `mvn clean test` để loại bỏ class cũ trong target, tránh trùng Spring bean/entity. Bộ test này chưa xác nhận schema trên PostgreSQL thực tế.

## Lộ trình tiếp theo

1. Kiểm tra schema thực tế PostgreSQL và dữ liệu khởi tạo.
2. API quản lý tổ chức, user, nhóm, chức danh, gán quyền và ghi audit.
3. Domain → Program → Course → Chapter → Lesson → Blocks và file storage.
4. Enrollment, assignment, learning progress.
5. Questions, quiz attempts, server-side scoring.
6. Dashboard, reports, skill evidence và các module mở rộng.

## Quy ước Lombok

- Entity: @Getter, @Setter, @NoArgsConstructor; không dùng @Data, @ToString hoặc sinh equals/hashCode tự động trên entity/quan hệ JPA. Constructor rỗng và giá trị mặc định giữ nguyên.
- CreatedEntity/UpdatedEntity chỉ @Getter; không mở setter cho timestamp do persistence quản lý.
- Service/controller/security có dependency final dùng @RequiredArgsConstructor; vẫn là constructor injection.
- DTO dùng record tiếp tục giữ record: constructor/accessor đã được Java sinh. LoginRequest, TokenResponse và JwtProperties có toString che secret, không log trực tiếp credential/token.
- Lombok version theo Spring Boot BOM; lombok-mapstruct-binding 0.2.0 nằm trên annotation processor path để mapper nhận getter sinh bởi Lombok.
- IntelliJ Reload Maven sau thay đổi; bật Annotation Processing nếu IDE chưa nhận mã sinh. lombok.config ở root được copy vào Docker build.
