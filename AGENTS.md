# Hướng dẫn chung cho developer và coding agent

Áp dụng cho toàn repository. Đây là nguồn quy tắc chung cho Codex, Claude và các agent khác; file hướng dẫn riêng của công cụ chỉ dẫn chiếu về đây.

## Cách làm việc

- Đọc code hiện tại, yêu cầu của người dùng và tài liệu liên quan trước khi sửa.
- Ưu tiên yêu cầu trực tiếp của người dùng so với quy ước trong repository; không được bỏ qua các chỉ dẫn hệ thống của công cụ.
- Đọc thêm AGENTS.md trong thư mục con nếu có. Quy tắc riêng áp dụng trong phạm vi thư mục đó.
- Nếu tài liệu và implementation mâu thuẫn, xác minh bằng source/config đang dùng và nêu rõ điểm lệch; không tự thay đổi thiết kế chỉ để khớp tài liệu cũ.
- Giữ thay đổi của người dùng và phần ngoài phạm vi công việc. Không reset, clean, checkout hay ghi đè thay đổi để có workspace sạch.
- Hoàn tất phần việc đã được yêu cầu; chỉ hỏi khi thiếu thông tin ảnh hưởng đáng kể đến kết quả hoặc hành động vượt phạm vi được giao.
- Không tự commit, push, deploy hoặc thay đổi dữ liệu/schema trên server khi nhiệm vụ chưa yêu cầu. Khi đã được yêu cầu, thực hiện trong đúng phạm vi đó.

## Đọc tài liệu theo công việc

| Công việc | Tài liệu |
|---|---|
| Cấu trúc module, layer, Java/JPA/mapper | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Dependency và thư viện nền | [docs/BASE_LIBRARIES.md](docs/BASE_LIBRARIES.md), [pom.xml](pom.xml) |
| Envelope, request ID, validation và lỗi API | [docs/API_CONTRACT.md](docs/API_CONTRACT.md) |
| Login, JWT, me, logout và contract frontend | [docs/AUTH_API.md](docs/AUTH_API.md) |
| Identity, tổ chức, RBAC và SQL | [docs/sql/README.md](docs/sql/README.md), [docs/sql/learning_hub_identity.sql](docs/sql/learning_hub_identity.sql) |
| Logging | [docs/LOGGING.md](docs/LOGGING.md) |
| Docker/Ubuntu và CI/CD | [docs/DEPLOY_UBUNTU.md](docs/DEPLOY_UBUNTU.md), [docs/CICD.md](docs/CICD.md) |

Các tài liệu mô tả thiết kế và hiện trạng, không phải bằng chứng rằng tính năng hoặc kiểm thử đã tồn tại; kiểm tra source tương ứng.

## Kiến trúc và Java

- Giữ modular monolith, package gốc `vn.com.coreacademy`. Tổ chức theo chức năng; bên trong module chia controller, service, repository, entity, dto và các package hỗ trợ khi cần.
- Controller xử lý HTTP/validation; service xử lý use case và transaction; repository truy cập dữ liệu. Không trả JPA entity trực tiếp qua API.
- Thành phần thuộc nghiệp vụ giữ trong module. Chỉ đưa vào common khi dùng chung và không thuộc riêng nghiệp vụ nào; không tạo utils/constants chung để gom mã không liên quan.
- DTO ưu tiên Java record; mapping dùng MapStruct với CentralMapperConfig. Không thêm Lombok vào record chỉ để sinh accessor/constructor đã có.
- Entity dùng Lombok Getter/Setter/NoArgsConstructor có chọn lọc; không dùng Data hoặc tự sinh toString/equals/hashCode qua quan hệ JPA. Timestamp do persistence quản lý không mở setter tùy tiện.
- Constructor injection qua RequiredArgsConstructor và field final. Giữ xử lý đặc biệt trong constructor nếu có.
- Version Java/compiler lấy từ pom.xml, runtime/container kiểm tra Dockerfile. Không tự nâng Java, Spring Boot hoặc dependency ngoài phạm vi; ưu tiên version do Spring Boot BOM quản lý.

## API, database và bảo mật

- JSON API nghiệp vụ nhận DTO request trực tiếp, không bọc data hoặc kế thừa base request; response dùng BaseResponse<T>; lỗi MVC/Security cùng envelope, giữ HTTP status. Request ID ở X-Request-Id/MDC. Đọc docs/API_CONTRACT.md; không bọc Swagger/health/file/stream.
- Giữ API dưới `/api/v1`; dùng DTO request/response rõ ràng và cập nhật Swagger khi đổi contract. Không đổi tên trường hoặc cấu trúc response ngoài phạm vi nhiệm vụ.
- Local dùng một file `.env` ở thư mục chạy, application.yml tự import. Không tạo thêm profile/file env local khi chưa cần. Cấu hình server/CI độc lập, không ghi đè bằng secret local.
- Không in, log hoặc commit password, password hash, JWT, signing secret hay private key. Không tin rằng .gitignore đã bảo vệ secret; kiểm tra file được chọn trước khi commit.
- Identity compact dùng 8 bảng trong schema learning_hub; password LOCAL ở users.password_hash. User ID là chuỗi, phải giữ số 0 đầu; login hỗ trợ mã cán bộ hoặc username và từ chối định danh mơ hồ.
- Các quan hệ database là logic, không tự thêm FOREIGN KEY vật lý. Service ghi dữ liệu phải kiểm tra tham chiếu, team thuộc org và chu trình cây đơn vị. Không suy BRN/BRCD/CRA thành quan hệ cha-con.
- Giữ ddl-auto=validate và Flyway tắt. Không bật migration legacy; SQL tạo mới không phải migration cho database có dữ liệu.
- Role và scope phải thuộc cùng một grant. Backend xác định org/team/owner từ dữ liệu thật; không tự kế thừa quyền xuống org con hoặc coi quyền màn hình là quyền CREATE/UPDATE/DELETE.
- JWT subject là mã cán bộ; kiểm tra trạng thái và auth_ver ở backend. Logout hiện thu hồi mọi phiên qua auth_ver; không đổi ngữ nghĩa này ngoài phạm vi nhiệm vụ.
- Logging dùng SLF4J với Log4j2; cấu hình và retention theo docs/LOGGING.md. Không bật log body/Authorization header cho API xác thực.

## Kiểm tra và hoàn tất

- Dùng rg để tìm code; đọc cấu hình thực tế trước khi chọn lệnh build/run.
- Với thay đổi Java, dependency hoặc annotation processor: chạy `mvn clean verify` từ root khi môi trường cho phép. Không thêm dependency hoặc framework test chỉ để đáp ứng checklist.
- Chạy kiểm thử phù hợp rủi ro và hành vi thay đổi; không tạo test chỉ lặp lại implementation. Nếu không có test, báo rõ Maven không chạy test, không gọi build thành công là test pass.
- Nếu kiểm tra API thật, dùng dữ liệu/tài khoản đã được cho phép; không tự seed, reset password hoặc gán quyền để làm test pass. Postman CLI có sẵn và có thể dùng khi phù hợp.
- Kiểm tra Docker/CI khi đổi build hoặc filesystem runtime; nếu chưa chạy Docker thực tế thì nêu rõ.
- Cập nhật tài liệu liên quan khi thay đổi contract, schema, cấu hình hoặc quyết định kiến trúc. Không lưu lịch sử hội thoại, credential hay log tạm vào tài liệu chuẩn.
- Báo ngắn gọn: thay đổi gì, đã kiểm tra gì, kết quả và giới hạn/rủi ro còn lại. Không khẳng định kiểm thử chưa thực hiện.

## Bổ sung chuẩn theo thời gian

- Cập nhật quy tắc đã thống nhất cùng PR thay đổi code. Đề xuất chưa chốt phải được ghi rõ là đề xuất.
- Mỗi chủ đề có một nguồn chính thức; bổ sung liên kết thay vì sao chép quy tắc sang nhiều file.
- Khi cần chuẩn riêng cho module, thêm AGENTS.md tại module và chỉ ghi phần khác biệt. Khi có quyết định thiết kế đáng lưu, có thể bổ sung docs/decisions và dẫn chiếu từ tài liệu liên quan.
- Đưa các quy tắc kiểm tra tự động được vào Maven/CI khi có yêu cầu phù hợp; tài liệu hướng dẫn không thay thế kiểm tra tự động.
