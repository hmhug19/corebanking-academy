# Kiến trúc Corebanking Academy

Tài liệu này mô tả cấu trúc và luồng đang có. Quy tắc phát triển, phân lớp, MapStruct/Lombok và format được quản lý tập trung tại [DEVELOPMENT_GUIDELINES.md](../DEVELOPMENT_GUIDELINES.md#java-conventions); không duy trì bộ convention thứ hai tại đây.

## Baseline và cấu trúc

Modular monolith với package `vn.com.coreacademy`. Phiên bản thực tế lấy từ pom.xml và Dockerfile: Spring Boot 3.5.16, compiler target Java 17, container build/runtime Java 21. Không suy phiên bản từ tên repository hoặc tài liệu cũ.

```text
vn.com.coreacademy
├── CoreAcademyApplication
├── config                         # Wiring toàn ứng dụng
│   ├── SecurityConfig / JwtProperties
│   └── CorsConfig / OpenApiConfig
├── security                       # Caller và HTTP security adapters dùng chung
│   ├── SecurityContextUtils (static)
│   └── ApiSecurityErrorHandler / SecurityConstants
├── common                         # Không phụ thuộc nghiệp vụ
│   ├── constants                  # HttpHeaderNames; chuẩn HTTP dùng thư viện sẵn có
│   ├── dto / exception            # Envelope và lỗi chung
│   ├── web                        # RequestIdFilter, RequestIdContext, PageQuery
│   └── mapper / persistence / validation
├── storage                        # S3 config/client và put/stat/read/delete dùng chung
├── identity
│   ├── controller / service / repository / entity / dto / mapper
│   ├── constants                  # RoleCodes
│   ├── security                   # Account-aware adapters và PasswordPolicy
│   └── bootstrap / converter / enums / exception
├── organization                   # API, use case và persistence org/team, import Excel
├── audit                          # Ghi audit cùng transaction
├── learning                        # Danh mục, nội dung, giao học, tài nguyên và tiến độ
├── knowledge                       # Kho tri thức Trợ lý AI: tải lên, worker nền, RAG cục bộ (Ollama + pgvector)
├── assistant                       # Trợ lý nhanh: vòng lặp agent có công cụ (Ollama), yêu cầu chờ xác nhận
├── assessment                      # Quiz Engine P0: câu hỏi, cấu hình đề, lượt thi, chấm và job tự nộp
└── reporting / ...                 # Phạm vi nghiệp vụ chưa triển khai
```

Identity có controller/service/repository/entity/dto/mapper/security; Organization có API org/team; Audit ghi
sự kiện trong transaction. Learning có 36 operation và 26 bảng scalar-ID theo [LEARNING.md](api/LEARNING.md),
truy cập hồ sơ/scope qua LearningIdentityService của Identity. Quiz Engine, báo cáo năng lực và gamification
chưa triển khai; không suy chức năng đã có từ tên package hoặc bảng điểm nối assessment.

Knowledge là kho tài liệu dùng chung (mọi vai trò): bảng `knowledge_documents` đồng thời là hàng đợi cho worker
`@Scheduled` (trích xuất theo trang/dòng → chia đoạn → nhúng bge-m3 → pgvector). Chi tiết contract, cấu hình và
luồng xử lý tại [KNOWLEDGE.md](api/KNOWLEDGE.md); chỉ gọi Ollama cục bộ, không gửi dữ liệu ra ngoài.

Assistant chạy vòng lặp agent (`AgentService`) với các công cụ (`AgentTool`) mà người dùng được dùng. Công cụ chỉ đọc
chạy ngay; công cụ thay đổi dữ liệu trở thành yêu cầu chờ trong bộ nhớ (`PendingActionStore`) và chỉ chạy khi người
dùng xác nhận. Contract tại [ASSISTANT.md](api/ASSISTANT.md).

Community đã có source C01–C138, hiện bật 135 API và tạm comment controller AI C126–C128:
[DB Community](api/COMMUNITY_DATABASE.md).
Tài liệu gồm 51 bảng cho toàn bộ Q&A, Blog/video, Guild/Wiki/Radar, review kiến trúc, digest, đóng góp/huy hiệu, thống kê, Learning links và AI có nguồn;
quan hệ scalar ID không có FK vật lý. Contract toàn bộ phạm vi, ma trận quyền/vòng đời, upload và checklist nghiệm thu
tại [COMMUNITY.md](api/COMMUNITY.md). Đã có baseline SQL/mapping 51 bảng, 138 route và các worker;
trạng thái từng nhóm nằm ở đầu contract, cách gọi tại [COMMUNITY_CURL.md](api/COMMUNITY_CURL.md).
Baseline chưa được chạy trên server.

## Ranh giới xử lý

```text
HTTP → controller (request/validation/caller)
     → service (use case/quyền/transaction)
     → repository → PostgreSQL
     → mapper/DTO → BaseResponse
```

Common chứa contract, exception, persistence base, mapper config, validation và web helpers dùng chung. Cấu hình hạ tầng ở root config; cấu hình riêng nghiệp vụ nằm trong module. Identity/Organization administration phối hợp repository trong transaction để giữ quan hệ logic, không có FK vật lý. Chưa có công cụ tự động cưỡng chế ranh giới module.

Import tổ chức đi qua OrganizationImportController → OrganizationImportService. OrganizationImportWorkbook
trong organization.importing đọc template từ classpath `templates/organization-import-template.xlsx`,
dùng Apache POI đọc tệp nhập, giới hạn XLSX/ZIP/dòng và dùng UploadStaging để dọn file tạm.
Service dùng constraint DTO tạo mới hiện có, tra mã/tham chiếu theo lô và sắp cha trước con. Parse nằm ngoài
transaction/lock; dry-run chỉ đọc. Nhập thật dùng IdentityWriteLock, kiểm tra lại scope/trùng/tham chiếu và ghi
org/team cùng audit nguyên tử. Không có job nền, lưu Excel trên S3, thay schema hoặc tự tạo role/membership.
Chi tiết template và luồng FE ở [Identity import](api/IDENTITY.md#organization-import).

## Xác thực và phân quyền

```text
POST /api/v1/auth/login
 → AuthController → AuthService → AuthenticationManager
 → LocalUserDetailsService → LoginUserResolver → users
 → BCrypt LOCAL credentials → TokenService → JWT

GET /api/v1/auth/me
 → RequestIdFilter → Spring Security/JwtDecoder
 → UserJwtAuthenticationConverter (status/auth_ver)
 → AuthController → AuthService lấy userId qua SecurityContextUtils
 → AuthorizationService → MeResponse

Administrative API
 → controller chuyển request → service tự lấy caller qua SecurityContextUtils
 → IdentityAccessPolicy (action + scope từ DB)
 → query có scope trước count/pagination
 → transaction + IdentityWriteLock → persistence + audit
```

IdentityAccessPolicy xác định phạm vi quản trị; AuthorizationService dùng cùng quy tắc cây ORG cho quyền theo role. PostgreSQL advisory transaction lock điều phối admin writes giữa các instance. PATCH DTO dùng field-presence để phân biệt bỏ qua và null; read-model mapping dùng MapStruct.

## Persistence và contract

- [Sơ đồ DB Learning](api/LEARNING.md#sơ-đồ-quan-hệ-dữ-liệu): ảnh các nhóm bảng nội dung, giao học và tiến độ,
  kèm cách đọc quan hệ bằng ID và hướng dẫn từng bảng.

- Tám bảng compact: users, orgs, teams, roles, functions, role_functions, user_roles, audit_log. Chi tiết timestamp/type/quan hệ tại [SQL README](sql/README.md) và schema.
- Cụm Identity có một script khởi tạo learning_hub_identity.sql, gồm schema và seed demo với users.must_change_password. Script chỉ dùng cho database mới/tạo lại; hướng dẫn tại [SQL README](sql/README.md).
- [API_CONTRACT.md](api/API_CONTRACT.md): envelope, request ID, validation và lỗi MVC/Security.
- [Contract Identity](api/IDENTITY.md): 21 API core xác thực, hồ sơ, quản trị tài khoản, org/team và role/scope, cùng 2 API đọc cây/panel tổ chức và template/import Excel.

## Request và caller context

RequestIdFilter là Servlet component chạy trước Spring Security, dùng chung HttpHeaderNames.REQUEST_ID với CORS. Filter giữ ID qua request attribute khi dispatch lại, đặt X-Request-Id/MDC và khôi phục MDC trong finally; không cache/log body hoặc kiểm tra role. Chỉ đăng ký một lần; chưa thêm access-log filter hoặc executor propagation.

SecurityContextUtils là class final với static methods isAuthenticated(), getUserId(), getAuthorizationVersion(). User ID lấy từ sub, auth version lấy từ av trong JWT đã xác thực. Hai getter từ chối context thiếu/sai thay vì trả anonymous/null. Helper không có bean/model caller riêng, không chứa user profile/role, không đọc header, decode lại token hoặc gọi DB/Redis/HTTP.

Use case cần user hiện tại tự lấy userId tại service, controller không truyền caller qua mọi lời gọi. Các policy/audit/helper nội bộ vẫn nhận actorId khi cần phối hợp trong transaction. Những use case tự lấy context phụ thuộc context xác thực; job hoặc @Async không tự có user hiện tại, phải thiết lập/truyền context tin cậy rõ ràng. Không dùng userId target trong request làm actor.

SecurityConfig ở cấp ứng dụng wiring các bean xác thực và UserJwtAuthenticationConverter của Identity. JwtProperties vẫn bind app.jwt như trước. Identity giữ LocalUserDetailsService, kiểm tra tài khoản/status/auth_ver, phát token và policy role/scope. constants kỹ thuật, mã role và giá trị môi trường có chủ sở hữu riêng, không gom thành một registry toàn cục.

21 endpoint giữ URL, body và response contract; controller không còn Authentication parameter nên không cần @Parameter(hidden=true) để ẩn caller trong Swagger. Logout vẫn thu hồi mọi phiên bằng auth_ver của token hiện tại.

Chính sách HTTP cache chung đặt tại SecurityConfig qua cache-control header writer của Spring Security: Cache-Control: no-cache, no-store, max-age=0, must-revalidate; Pragma: no-cache; Expires: 0. Controller và exception handler không lặp chính sách này. Endpoint có nhu cầu cache khác có thể khai báo header riêng; phải kiểm chứng response thực tế khi thêm override.

## Quản trị gốc và bootstrap

SUPER_ADMIN GLOBAL có quyền nghiệp vụ toàn hệ thống và quyền quản trị ADMIN/SUPER_ADMIN. ADMIN GLOBAL/ORG chỉ quản lý tài khoản thường theo scope; tài khoản mang vai trò quản trị được bảo vệ khỏi sửa hồ sơ, trạng thái, chuyển tổ chức/nhóm hoặc thay đổi grant bởi ADMIN. IdentityAccessPolicy.Boundary tách global (phạm vi dữ liệu) khỏi superAdmin (quyền quản trị gốc); không suy cấp quyền quản trị từ global.

Gợi ý editable/revocable dùng cùng policy với kiểm tra ghi. SUPER_ADMIN active cuối cùng được bảo vệ bằng kiểm tra sau flush trong transaction giữ IdentityWriteLock; ADMIN GLOBAL không thay thế điều kiện này. API hiện có giữ nguyên route/schema, /roles dùng assignableScopes để giới hạn các vai trò được cấp và thêm capability ADMIN_ROLE_GRANT cho SUPER_ADMIN.

Bootstrap cấu hình bằng ba biến APP_BOOTSTRAP_ADMIN_ID/USER/PASSWORD hiện có: chỉ tạo user LOCAL mới với SUPER_ADMIN GLOBAL khi chưa có root active, bắt buộc đổi mật khẩu và ghi audit cùng transaction. User đã tồn tại giữ nguyên mật khẩu/quyền khi restart. Mật khẩu tạo mới, đổi hoặc bootstrap từ 6 ký tự đến 72 byte UTF-8. Script SQL khởi tạo mới đã seed super.admin nên không cần bootstrap Java khi dùng bộ dữ liệu demo. Database hiện có cần giữ dữ liệu phải có kế hoạch thay đổi riêng, không tự nâng quyền ADMIN hiện có.

<a id="libraries"></a>

## Thư viện và build

Phiên bản và dependencies lấy từ pom.xml; Dockerfile xác định JDK/JRE của container. Không duy trì danh sách đề xuất dependency chưa sử dụng.

| Nhóm | Thành phần hiện có |
|---|---|
| REST / validation | Spring MVC, Jackson, Bean Validation |
| Persistence | Spring Data JPA, Hibernate, PostgreSQL JDBC, HikariCP |
| Security | Spring Security, OAuth2 Resource Server, BCrypt |
| Mapping | MapStruct 1.6.3, CentralMapperConfig, mapper trong từng module |
| Code generation | Lombok theo Boot BOM, lombok-mapstruct-binding, Spring Boot configuration processor |
| Vận hành | Actuator, SLF4J + Log4j2; loại logging mặc định Logback |
| Kiểm thử | Spring Boot Test, JUnit/Mockito, Spring Security Test; dependency không có nghĩa là đã có test |
| Schema | Flyway có dependency nhưng tắt; khởi tạo thủ công bằng SQL Identity |

Annotation processors chạy tại compile; mapper sinh ở target/generated-sources/annotations và metadata cấu hình là output build, không sửa/commit. IntelliJ dùng JDK 21 cho Project/Maven Runner, Reload Maven và bật annotation processing. Quy tắc Java, Lombok và MapStruct nằm tại guideline chung.

## Giới hạn kiểm chứng

Repository hiện có test của Knowledge. Maven verify phải kiểm tra kết quả thực tế và các test bị skip do thiếu Docker; compile/package thành công không đồng nghĩa toàn bộ test pass. Smoke check cục bộ với H2 không thay thế kiểm tra PostgreSQL, advisory lock nhiều instance, Docker hoặc môi trường triển khai thật. Nhật ký kiểm tra cá nhân nằm ngoài tài liệu contract được commit.


## Search và custom repository

User và Organization search dùng repository fragment tại identity.repository.custom và organization.repository.custom. Service xác định scope qua IdentityAccessPolicy, kiểm tra filter rồi gọi searchUsers/searchOrganizations; không còn Specification/JpaSpecificationExecutor. Danh sách team chỉ lọc orgId dùng derived query có Pageable, không tạo fragment riêng.

NativeSearchQuery tại common.persistence dùng chung FROM/WHERE/parameters cho list/count, escape LIKE literal, ánh xạ sort qua whitelist và phân trang tại DB. Mỗi search tạo một instance; helper không biết bảng hoặc role. Filter rỗng được giữ thành điều kiện không có kết quả. PageableExecutionUtils tránh count khi suy ra chính xác từ page.

UserSearchRow là read model chỉ gồm cột cần cho UserSummary, không có password/hash hoặc metadata nguồn. Role filter dùng EXISTS để một user nhiều grant vẫn chỉ chiếm một dòng. Role labels vẫn được lọc theo cùng policy; visibleGrantIds tải org của các team theo lô, không query theo từng grant. findActiveForUsers chỉ fetch role; lấy user ID từ association không cần tải cả hồ sơ tài khoản.

Search vẫn dùng contains (`%q%`) và OFFSET theo contract hiện tại; B-tree không tự bảo đảm tăng tốc contains và offset lớn vẫn có chi phí. Chưa thêm index/trigram/keyset pagination hoặc sửa schema; cần đo PostgreSQL với dữ liệu đại diện trước khi quyết định. Native SQL không được coi là bằng chứng tự thân về hiệu năng.

## Tài nguyên Learning và AIStor

S3/AIStor là kho lưu file lâu dài duy nhất cho tài nguyên Learning. Knowledge từ thay đổi song song vẫn có storage-dir riêng, chưa được chuyển trong task này. Module storage ngang hàng Learning/Identity: storage.config bind app.storage.s3 và wiring SDK; ObjectStorage cung cấp put/stat/read/delete, không có public controller hay policy nghiệp vụ. Learning giữ validation, quyền, metadata/audit và cleanup khi DB rollback. Không đặt client S3 vào common.utils và không có adapter/fallback local.

UploadStaging quản lý file tạm và SHA-256 theo stream; StagedFile đóng bằng try-with-resources để dọn file cả khi validation/upload lỗi. LearningAssetStore giữ kiểm tra signature/extension, giới hạn byte, metadata video/PDF, key learning/UUID và cleanup khi PUT lỗi. LearningAssetService kiểm quyền, lưu storageType=s3, metadata/audit và dọn object khi DB rollback. Hai nhánh cleanup giữ tại nơi quyết định thất bại vì S3 và DB không có transaction chung.

GET content: LearningAssetController → LearningAssetService.download (kiểm quyền/metadata trong transaction ngắn) → ObjectDownloadHandler (HEAD/GET S3, Range/If-Range, header, stream đồng bộ 64 KiB). Metadata ObjectDownload là DTO nội bộ, không trả key qua API; ByteRange là chi tiết package storage.web. Controller không giữ helper stream và không giữ transaction khi truyền file. Range hợp lệ trả 206, sai hoặc multiple ranges trả 416, HEAD không tải object. Lỗi trước stream theo BaseResponse; lỗi sau commit không chèn JSON vào binary.

SDK MinIO 9.0.3 với OkHttp JVM 5.3.2/Kotlin runtime 2.2.21; một bucket private academy-media, endpoint/credential qua S3_* bắt buộc. Không tạo bucket, không mở anonymous, không tự lấy root credential. Compose nối project_edge và dùng disk volume riêng cho file tạm. HealthUP hiện tại không kiểm khả năng đọc/ghi S3.

Không thay schema assets; storage_type/key đã có. Row local cũ trả LEARNING-ASSET_STORAGE_UNSUPPORTED, không tự đổi metadata hoặc xóa file; chuyển dữ liệu cần kế hoạch riêng. [Triển khai và nghiệm thu](operations/DEPLOY_UBUNTU.md#aistor-integration) mô tả môi trường Ubuntu, tunnel local, cấu hình và các giới hạn kiểm chứng.
