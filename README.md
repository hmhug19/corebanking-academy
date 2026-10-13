# CORE Academy Backend — Modular Monolith Starter

Spring Boot **3.5.16 GA** / Java **21 LTS** / Maven / PostgreSQL / Flyway / Spring Security / JWT.

Nhánh Spring Boot 3.5 dùng bản GA 3.5.16, tương thích Java 21 theo [system requirements chính thức](https://docs.spring.io/spring-boot/3.5/system-requirements.html). Maven compile/release target 21; Docker build và runtime đều dùng Temurin 21. Dependency version được quản lý bởi Spring Boot parent, không pin riêng Spring/Hibernate/Jackson.

MapStruct 1.6.3 đã cấu hình và áp dụng trong `identity.mapper.UserMapper`; quy ước và đánh giá các thư viện khác nằm trong [BASE_LIBRARIES.md](docs/BASE_LIBRARIES.md). Spring Boot configuration processor sinh metadata cho cấu hình; generated code chỉ nằm trong `target`.

## Trạng thái

Source tổ chức theo module chức năng, bên trong chia `controller`, `service`, `repository`, `entity`, `dto` và các package hỗ trợ. `identity` là module mẫu đăng nhập/phân quyền; organization và audit có entity/repository. Xem [quy ước kiến trúc](docs/ARCHITECTURE.md).

Source đã mapping đủ 13 bảng trong schema `learning_hub` theo `docs/sql/learning_hub_identity.sql`: user ID dạng chuỗi, username, credentials riêng, tổ chức, nhóm, chức danh, role/permission và audit. Database name trong JDBC URL khác schema name: script có thể chạy trong database `appdb` hoặc database khác; các entity luôn chỉ rõ schema `learning_hub`.

**Starter, không phải sản phẩm LMS hoàn chỉnh.** Đã có: login bằng username hoặc mã cán bộ, `/me`, JWT HS256, multi-role/permissions và kiểm tra scope GLOBAL/ORG/TEAM/OWN theo context được cung cấp. Thông tin mật khẩu nằm trong `user_credentials`, không nằm trong `users`.

**Chưa có:** course API và hierarchy, question bank/quiz engine, tiến độ học, content upload/player, reporting, UI Angular, audit event writers, refresh token/SSO và user-management APIs. Entity/repository không đồng nghĩa đã có API quản trị.

## Yêu cầu

Triển khai backend bằng Docker trên Ubuntu: xem [hướng dẫn build/deploy](docs/DEPLOY_UBUNTU.md). Dùng `Dockerfile`, `compose.ubuntu.yaml` và `.env.ubuntu.example`; PostgreSQL hiện có được cấu hình qua biến môi trường.

- JDK 21 cho build/chạy local; Docker dùng JDK/JRE 21
- Maven 3.6.3+ (khuyên Maven 3.9.x)
- PostgreSQL 14+; Docker Compose là tùy chọn
- Không cần cài Nginx để chạy backend local

IntelliJ: chọn **Project SDK = JDK 21**, Language level 21 và Maven Runner JRE = Project SDK/JDK 21, rồi Reload Maven. Khi chạy terminal, đặt JAVA_HOME tới JDK 21 và kiểm tra `java -version`, `mvn -version`. File `.java-version` ghi phiên bản chuẩn; file này không tự cài hoặc cấu hình JDK cho IntelliJ.

## Chạy local

```bash
cp .env.example .env
# Sửa DB_PASSWORD, JWT_SECRET và bộ APP_BOOTSTRAP_ADMIN_ID/USER/PASSWORD. Không commit .env.
# Chạy PostgreSQL: docker compose --env-file .env up -d db
# Export các biến môi trường trong shell (Docker Compose không tự export cho Maven):
set -a; source .env; set +a
mvn clean test
# Chạy docs/sql/learning_hub_identity.sql trên database đích trước khi chạy app.
mvn spring-boot:run
```

Nếu shell không hỗ trợ `source`, export biến môi trường bằng cách khác. Đợi PostgreSQL healthy trước khi chạy Spring Boot.

Spring Boot tự đọc file `.env` ở thư mục chạy nhờ `spring.config.import`. Khi chạy IntelliJ, đặt Working directory là thư mục project. File dùng định dạng properties `KEY=value`, không dùng `export` hoặc bọc giá trị bằng dấu nháy. Biến môi trường vẫn ghi đè giá trị trong `.env`; không đặt `JWT_SECRET` rỗng trong Run Configuration. `.env` đã được ignore, không commit secret. Nếu file không tồn tại thì cấu hình lấy từ biến môi trường.

## API thử

```bash
curl http://localhost:8019/actuator/health
curl -X POST http://localhost:8019/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<APP_BOOTSTRAP_ADMIN_PASSWORD>"}'
# Lấy accessToken từ response, sau đó:
curl -H 'Authorization: Bearer <TOKEN>' http://localhost:8019/api/v1/auth/me
# Trường username cũng nhận mã cán bộ; JWT subject và /me.user.id giữ nguyên chuỗi, kể cả số 0 đầu.
```

Bootstrap admin cần đủ `APP_BOOTSTRAP_ADMIN_ID`, `APP_BOOTSTRAP_ADMIN_USER` và `APP_BOOTSTRAP_ADMIN_PASSWORD`; để trống cả ba nếu không dùng bootstrap. Chạy SQL seed trước. User, credentials và GLOBAL ADMIN assignment được tạo trong một transaction; không tự reset account đã tồn tại. Script chỉ seed danh mục, không seed user hoặc đơn vị.

## API có sẵn

- `POST /api/v1/auth/login`: login, nhận JWT Bearer ngắn hạn
- `GET /api/v1/auth/me`: user, role assignments, effective permissions và scopes
- `GET /actuator/health`: health check

## Phân quyền

- `learning_hub.users` → `user_roles` → `roles` → `role_permission` → `permission`.
- `GLOBAL` cho toàn hệ thống; `ORG` khớp tổ chức; `TEAM` phải khớp cả tổ chức và nhóm; `OWN` khớp owner ID của resource do backend cung cấp.
- Không tự kế thừa ORG xuống tổ chức con. Context phân quyền phải lấy từ dữ liệu server, không tin org/team/owner ID do browser khai báo.
- `role.manage` và `position.manage` chỉ chấp nhận GLOBAL. Việc gán role vượt quyền còn cần kiểm tra nghiệp vụ khi có API quản trị.
- Angular sử dụng `/auth/me` để hiện menu. Mọi API vẫn kiểm tra server-side.
- Không nên nhét role/permission vào JWT trong cấu hình này; server đọc RBAC từ DB khi cần.

Trạng thái user trong `/me` là `active/locked/disabled`. Scope response chứa `type`, `orgId`, `teamId`. Username được lưu chữ thường, login username không phân biệt hoa/thường. Đầu vào khớp hai user qua ID và username sẽ bị từ chối.

Flyway đang tắt. Các migration cũ trong resources chỉ là bản legacy, không tương thích schema mới; không bật lại để khởi tạo schema mới. Hibernate giữ `ddl-auto=validate`, không tự tạo hoặc sửa database.

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
