# CORE Academy Backend — Modular Monolith Starter

Spring Boot **3.5.16 GA** / Java **21 LTS** / Maven / PostgreSQL / SQL thủ công / Spring Security / JWT.

Nhánh Spring Boot 3.5 dùng bản GA 3.5.16, tương thích Java 21 theo [system requirements chính thức](https://docs.spring.io/spring-boot/3.5/system-requirements.html). Maven compile/release target hiện tại là 17; Docker build và runtime đều dùng Temurin 21. Dependency version được quản lý bởi Spring Boot parent, không pin riêng Spring/Hibernate/Jackson.

MapStruct 1.6.3 đã cấu hình và áp dụng trong `identity.mapper.UserMapper`; quy ước và đánh giá các thư viện khác nằm trong [Thư viện và build](docs/ARCHITECTURE.md#libraries). Spring Boot configuration processor sinh metadata cho cấu hình; generated code chỉ nằm trong `target`.

## Trạng thái

Source tổ chức theo module chức năng, bên trong chia `controller`, `service`, `repository`, `entity`, `dto` và các package hỗ trợ. `identity` có xác thực và quản trị user/role; organization có API org/team và audit ghi sự kiện. Xem [kiến trúc hiện tại](docs/ARCHITECTURE.md); quy chuẩn phát triển/style dùng chung tại [DEVELOPMENT_GUIDELINES.md](DEVELOPMENT_GUIDELINES.md).

Source mapping 8 bảng compact trong schema `learning_hub` theo `docs/sql/learning_hub_identity.sql`: users, orgs, teams, roles, functions, role_functions, user_roles và audit_log. Hồ sơ giữ một org/team chính và position_name. Database name trong JDBC URL khác schema name: script có thể chạy trong database `appdb` hoặc database khác; các entity luôn chỉ rõ schema `learning_hub`.

**Starter, không phải sản phẩm LMS hoàn chỉnh.** Đã có: login bằng username hoặc mã cán bộ, `/me`, JWT HS256, multi-role, màn hình và scope và kiểm tra scope GLOBAL/ORG/TEAM/OWN theo context được cung cấp. Hash mật khẩu LOCAL nằm trong `users.password_hash`; SSO không đăng nhập bằng mật khẩu local.

**Learning đã có API:** danh mục/nội dung khóa, giao học, tiến độ và upload/download tài nguyên qua S3; xem [LEARNING.md](docs/api/LEARNING.md). Chưa có question bank/quiz engine, reporting, UI/player Angular và refresh token/SSO integration. Phạm vi 21 API core Identity cùng API cây/panel tổ chức và template/import Excel đã triển khai được mô tả trong [Contract Identity](docs/api/IDENTITY.md).

## Yêu cầu

Triển khai backend bằng Docker trên Ubuntu: xem [hướng dẫn build/deploy](docs/operations/DEPLOY_UBUNTU.md). Dùng `Dockerfile`, `compose.ubuntu.yaml` và file cấu hình riêng trên server; PostgreSQL hiện có được cấu hình qua biến môi trường.

- JDK 21 cho build/chạy local; Docker dùng JDK/JRE 21
- Maven 3.6.3+ (khuyên Maven 3.9.x)
- PostgreSQL 14+; Docker Compose là tùy chọn
- Không cần cài Nginx để chạy backend local

IntelliJ: chọn **Project SDK = JDK 21**, Language level 21 và Maven Runner JRE = Project SDK/JDK 21, rồi Reload Maven. Khi chạy terminal, đặt JAVA_HOME tới JDK 21 và kiểm tra `java -version`, `mvn -version`. File `.java-version` ghi phiên bản chuẩn; file này không tự cài hoặc cấu hình JDK cho IntelliJ.

## Chạy local

S3 là bắt buộc: bổ sung S3_ENDPOINT, S3_ACCESS_KEY, S3_SECRET_KEY vào .env hiện tại; bucket mặc định academy-media, region us-east-1, path-style true. Local mở SSH tunnel rồi dùng http://localhost:9000; BE Docker Ubuntu dùng http://aistor:9000. Xem [cấu hình storage](docs/operations/DEPLOY_UBUNTU.md#aistor-integration). Không tự fallback lưu local khi S3 lỗi.

```bash
# Cấu hình DB_URL/DB_USER/DB_PASSWORD, JWT_SECRET. Để trống bộ APP_BOOTSTRAP_ADMIN_* khi dùng seed demo.
# Chạy PostgreSQL: docker compose --env-file .env up -d db
# Spring Boot tự đọc file .env tại thư mục project.
mvn clean test
# Chạy docs/sql/learning_hub_identity.sql trên database đích trước khi chạy app.
mvn spring-boot:run
```

Nếu dùng PostgreSQL local qua Docker, đợi database healthy trước khi chạy Spring Boot.

Spring Boot tự đọc file `.env` ở thư mục chạy nhờ `spring.config.import`. Khi chạy IntelliJ, đặt Working directory là thư mục project. File dùng định dạng properties `KEY=value`, không dùng `export` hoặc bọc giá trị bằng dấu nháy. Biến môi trường vẫn ghi đè giá trị trong `.env`; không đặt `JWT_SECRET` rỗng trong Run Configuration. `.env` đã được ignore, không commit secret. Nếu file không tồn tại thì cấu hình lấy từ biến môi trường.

## API thử

```bash
curl http://localhost:8019/actuator/health
curl -X POST http://localhost:8019/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"super.admin","password":"Demo@123"}'
# Lấy accessToken từ response.data.accessToken, sau đó:
curl -H 'Authorization: Bearer <TOKEN>' http://localhost:8019/api/v1/auth/me
# Trường username cũng nhận mã cán bộ; JWT subject và /me.user.userId giữ nguyên chuỗi, kể cả số 0 đầu.
```

Script duy nhất [learning_hub_identity.sql](docs/sql/learning_hub_identity.sql) tạo đủ schema và dữ liệu demo, gồm super.admin (000001), quantri và các vai trò nghiệp vụ. Mật khẩu demo Demo@123, tài khoản seed không bắt buộc đổi mật khẩu; xem [danh sách tài khoản và cách chạy](docs/sql/README.md). Không cần APP_BOOTSTRAP_ADMIN_ID/USER/PASSWORD khi dùng bộ seed này. Bootstrap Java vẫn giữ cho trường hợp khởi tạo có chủ đích, không reset hoặc cấp lại quyền cho user đã tồn tại.

## API có sẵn

- `POST /api/v1/auth/login`: login, nhận JWT Bearer ngắn hạn
- `GET /api/v1/auth/me`: hồ sơ, mã role/function, roleGrants, functionDetails, functionScopes
- `POST /api/v1/auth/logout`: JWT Bearer, trả BaseResponse HTTP 200 và thu hồi token của mọi phiên
- `GET /actuator/health`: health check

## Phân quyền

- `learning_hub.users` → `user_roles` → `roles` → `role_functions` → `functions`.
- `GLOBAL` cho toàn hệ thống; `ORG` khớp tổ chức; `TEAM` phải khớp cả tổ chức và nhóm; `OWN` khớp owner ID của resource do backend cung cấp.
- ORG bao gồm node được gán và cây con active khi toàn bộ đường cha hợp lệ. Context phân quyền phải lấy từ dữ liệu server, không tin org/team/owner ID do browser khai báo.
- `AuthorizationService.requireRole` kiểm tra role và scope trên cùng grant. Mỗi API nghiệp vụ phải chọn role cho hành động cụ thể; quyền màn hình không cho phép tự động CREATE/UPDATE/DELETE. SUPER_ADMIN là role tường minh, không tự bypass API.
- Angular sử dụng `/auth/me` để hiện menu. Mọi API vẫn kiểm tra server-side.
- Không nên nhét role/permission vào JWT trong cấu hình này; server đọc RBAC từ DB khi cần.

Trạng thái user trong `/me` là `active/inactive/locked`. Scope response chứa `type`, `orgId`, `teamId`. Username được lưu chữ thường, login username không phân biệt hoa/thường. Đầu vào khớp hai user qua ID và username sẽ bị từ chối.

Flyway đang tắt; đã loại bỏ các migration legacy không tương thích. Chỉ dùng docs/sql/learning_hub_identity.sql để khởi tạo cụm Identity mới. Hibernate giữ `ddl-auto=validate`, không tự tạo hoặc sửa database.

## Lưu ý production / server RHEL 7.9

RHEL 7.9 đã cũ. Kiểm tra Java 21 runtime, TLS/repo, chính sách vận hành và hệ điều hành được hỗ trợ. Ưu tiên triển khai trên host/container Linux mới được duyệt, thay vì cài chồng nhiều runtime mới trực tiếp lên RHEL 7.9.

- Dùng HTTPS; không mở DB public; secrets qua hệ thống quản lý bí mật.
- Rate limit endpoint login, audit login & role changes, rotation key, monitoring, token revocation.
- Không sử dụng dữ liệu Core Banking thật hoặc thông tin nhạy cảm để seed môi trường demo.
- Khi chuyển major Spring Boot, thực hiện migration riêng và kiểm thử tương thích; bản chuẩn hiện tại là 3.5.16 GA.

## Team split

- Dev 1: identity, organization, authorization, deploy.
- Dev 2: learning, content, media.
- Dev 3: assessment, progress, reporting.
- Dev 4: Angular learner dashboard, course, lesson player.
- Dev 5: Angular admin, quiz, reporting.
- Dev 6: knowledge, issue, practice, skill integration.

## Swagger / OpenAPI

- Swagger UI: http://localhost:8019/swagger-ui.html
- OpenAPI JSON: http://localhost:8019/v3/api-docs
- Đổi host/port theo môi trường triển khai.

Trên UI, gọi POST /api/v1/auth/login bằng username hoặc mã cán bộ và mật khẩu.
Copy accessToken, bấm Authorize và chỉ dán token (không thêm Bearer), rồi gọi GET /api/v1/auth/me.
Trang tài liệu không yêu cầu login; API nghiệp vụ vẫn kiểm tra JWT và quyền.
Token không được lưu lại sau khi reload UI.

Mặc định SWAGGER_ENABLED=true. Đặt SWAGGER_ENABLED=false trong .env để tắt cả UI và API docs.
Compose Ubuntu và Compose CI truyền biến này vào container; recreate container sau khi đổi giá trị.
springdoc 2.9.1 thuộc dòng 2.x dành cho Spring Boot 3: https://springdoc.org/v2/

Logging dùng Log4j2, console và rolling file: [LOGGING.md](docs/operations/LOGGING.md).

Quy chuẩn request DTO, BaseResponse, request ID và lỗi: [API_CONTRACT.md](docs/api/API_CONTRACT.md).

## Tài liệu theo phạm vi

- Quy tắc phát triển: [DEVELOPMENT_GUIDELINES.md](DEVELOPMENT_GUIDELINES.md); AGENTS.md/CLAUDE.md là điểm vào cho agent.
- Thiết kế và thư viện hiện có: [ARCHITECTURE.md](docs/ARCHITECTURE.md).
- Triển khai tự động: [CI/CD](docs/operations/CICD.md).
- AIStor: [bàn giao Ubuntu, phần BE cần tích hợp và kế hoạch test](docs/operations/DEPLOY_UBUNTU.md#aistor-integration). Source đã tích hợp S3 private qua module storage; cần cấp S3_* trước khi chạy/deploy.
- Chỉ commit tài liệu dùng chung trong docs/api, docs/operations, docs/sql và kiến trúc. Ghi chú/nhật ký cá nhân để docs/local; mockup để docs/uiux. Hai thư mục này bị Git ignore.

## Identity administration

- [Contract Identity](docs/api/IDENTITY.md) — API core, cây/panel tổ chức, template/import Excel, form và luồng lỗi.
- Database mới/tạo lại: chạy duy nhất [learning_hub_identity.sql](docs/sql/learning_hub_identity.sql), gồm schema và seed demo. Database có dữ liệu cần giữ phải có kế hoạch thay đổi riêng; không chạy đè script khởi tạo.
