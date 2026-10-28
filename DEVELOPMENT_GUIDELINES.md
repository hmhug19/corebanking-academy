# Development guidelines — Corebanking Academy

> Quy chuẩn dành cho developer và AI Coding Agent khi phát triển, sửa lỗi, tối ưu và bảo trì dự án.
>
> **Mục tiêu:** đúng nghiệp vụ, dễ đọc, dễ bảo trì, hiệu năng phù hợp, an toàn và có thể kiểm chứng.
>
> Nguồn quy chuẩn phát triển chung cho developer, Codex, Claude và các coding agent. Đọc trước khi thực hiện nhiệm vụ liên quan source code. Quy tắc Java/style đã được gom tại đây; tài liệu API, SQL và vận hành chỉ giữ chi tiết theo phạm vi.

## 1. Phạm vi và mức độ ưu tiên

- **[BẮT BUỘC]** Ưu tiên yêu cầu nghiệp vụ, an toàn dữ liệu và hợp đồng API đã thống nhất.
- **[BẮT BUỘC]** Khảo sát source hiện hữu trước khi đề xuất thay đổi; không giả định framework, phiên bản hay convention khi chưa kiểm tra.
- **[BẮT BUỘC]** Không tự ý thay đổi nghiệp vụ, API contract, database schema, quyền truy cập hoặc dependency ngoài phạm vi task.
- **[KHUYẾN NGHỊ]** Ưu tiên *KISS, YAGNI, DRY, SOLID* theo cách thực dụng; tránh abstractions và patterns không cần thiết.
- Thứ tự áp dụng: chỉ dẫn hệ thống/công cụ → yêu cầu trực tiếp đã được người dùng chấp thuận → hướng dẫn repository theo phạm vi → tài liệu tham khảo. Hướng dẫn thư mục con chỉ bổ sung phần riêng trong phạm vi đó, không tự mâu thuẫn với quy tắc chung.
- Nếu tài liệu lệch source/config, xác minh và nêu rõ điểm lệch; không sửa nghiệp vụ chỉ để khớp tài liệu cũ. Chỉ hỏi khi còn thiếu quyết định nghiệp vụ quan trọng hoặc hành động vượt quyền đã được giao; không xin xác nhận lại việc người dùng đã cho phép.

**Nguyên tắc ưu tiên:**

1. Đúng nghiệp vụ trước, tối ưu sau.
2. Đơn giản, dễ hiểu và dễ maintain.
3. Tuân thủ kiến trúc và convention hiện tại.
4. Hạn chế thay đổi ngoài phạm vi.
5. Quan tâm hiệu năng, bảo mật và regression.
6. Luôn kiểm tra trước khi kết luận hoàn thành.

Hiệu năng không được đánh đổi bằng kết quả sai, mất an toàn dữ liệu hoặc phá vỡ contract đã thống nhất.

## 2. Quy trình thực hiện mỗi task

### 2.1 Understand

1. Xác định vấn đề, đầu vào/đầu ra, điều kiện nghiệm thu và các trường hợp biên.
2. Đọc code liên quan từ entry point tới luồng dữ liệu: FE → API → service → DB/integration.
3. Kiểm tra hành vi cũ cần giữ, chỗ có thể tái sử dụng, test hiện có và tác động tới module khác.
4. Với thông tin thiếu có thể làm sai nghiệp vụ, đặt câu hỏi ngắn gọn; nếu chỉ là chi tiết nhỏ, ghi giả định rõ ràng.

### 2.2 Design

- Đề xuất **một hướng xử lý khuyến nghị**, giải thích vì sao phù hợp, chỉ so sánh phương án khác khi có trade-off thật.
- Xác định phạm vi file/module thay đổi, database migration (nếu có), rủi ro rollback và backward compatibility.
- Task đơn giản: phân tích ngắn; task liên quan nhiều module/dữ liệu: lập kế hoạch và checkpoints trước khi code.

### 2.3 Implement

- Thay đổi nhỏ nhất **nhưng đủ để giải quyết nguyên nhân gốc**; không vá triệu chứng hoặc refactor lan rộng.
- Tôn trọng naming, formatting, tổ chức thư mục, error contract, logging và patterns sẵn có.
- Không sinh code giả, placeholder khó nhận diện, debug code, TODO không có lý do hoặc dependency dư thừa.
- Không chỉnh sửa file không liên quan. Nếu phát hiện vấn đề khác, ghi nhận riêng.

### 2.4 Verify

- Chạy format/lint, compile/build và test liên quan khi điều kiện cho phép.
- Kiểm tra happy path, edge cases, phân quyền, dữ liệu bất thường, khả năng tương thích và regression.
- Với thay đổi liên quan hiệu năng: kiểm tra query plan, số lượt gọi DB/API hoặc benchmark phù hợp khi có môi trường/dữ liệu.
- **Không nói đã test/build thành công nếu thực tế chưa chạy.** Ghi rõ bước chưa chạy và nguyên nhân.

### 2.5 Report

Báo cáo ngắn gọn: mục tiêu đạt được; file/module đã thay đổi; kiểm chứng đã chạy và kết quả; rủi ro còn lại; migration/configuration hoặc hành động cần triển khai.

## 3. Clean Code và kiến trúc

### 3.1 Cấu trúc và naming

- Tên class, method, biến và bảng phải thể hiện ý nghĩa nghiệp vụ; tránh từ mơ hồ (`data`, `process`, `handle`) khi có tên cụ thể hơn.
- Một hàm nên tập trung một nhiệm vụ; ưu tiên guard clause/early return để giảm nesting.
- Tách private method khi một cụm có tên nghiệp vụ rõ, nhiều nhánh hoặc làm luồng chính khó theo dõi. Giữ điều kiện đơn giản tại chỗ, không tách mỗi if thành một hàm. Ưu tiên private method/record nội bộ trong cùng class khi chỉ dùng ở đó; không tạo service/interface mới chỉ để rút ngắn hàm, không chia theo số dòng cứng.
- Tách API/input validation, orchestration, business rules, persistence và integration hợp lý; không nhồi tất cả vào controller hoặc UI.
- Dùng constants, enum hoặc cấu hình có chủ đích cho giá trị dùng nhiều nơi; tránh magic numbers/strings.
- Comment giải thích **vì sao** và ràng buộc nghiệp vụ, không diễn giải lại code tự hiểu được.
- Không tách interface, factory, mapper hoặc helper chỉ vì nguyên tắc hình thức; abstraction phải đem lại lợi ích thực.

### 3.2 Thực hành bảo trì

- Tái sử dụng component/service/utility hiện có khi phù hợp; đừng nhân bản logic nghiệp vụ.
- Tránh overengineering: microservice, event bus, caching, CQRS hay generic framework nếu không có yêu cầu và bằng chứng cần thiết.
- Giữ backward compatibility của API, migration, cấu hình và dữ liệu; thay đổi breaking phải được xác nhận.
- Giới hạn transaction/side-effect ở ranh giới rõ ràng; không gây tác động ngầm từ getter, mapper hoặc validation.

## 4. Backend — Java / Spring Boot

> Chỉ áp dụng khi repository thực tế dùng Java/Spring Boot; tuân thủ phiên bản và kiến trúc đang có. Nếu dự án là monolith, giữ kiến trúc modular monolith, không tự tách microservices.

- Controller mỏng: nhận request, validation, authorization và trả response phù hợp; business logic ở service/domain.
- DTO cho API; không trực tiếp expose JPA entity chứa field nội bộ/quan hệ nhạy cảm.
- Thống nhất validation tại boundary (`@Valid`/constraint hoặc cơ chế có sẵn), đồng thời kiểm soát business invariant ở service.
- Dùng exception mapping nhất quán; phân biệt validation, business conflict, unauthenticated, forbidden và lỗi nội bộ. Không trả stack trace cho client.
- Chỉ dùng `@Transactional` tại ranh giới nghiệp vụ cần atomicity; cân nhắc `readOnly=true` cho truy vấn thuần đọc; tránh gọi remote API bên trong transaction kéo dài.
- Tránh N+1 queries và eager loading không kiểm soát; dùng projection/batch fetch/join thích hợp theo dữ liệu thực tế.
- Không tải danh sách lớn để phân trang trong memory; đặt giới hạn page size ở API.
- Với gọi ngoài: cấu hình timeout, retry có giới hạn và chỉ retry thao tác an toàn/idempotent; cân nhắc circuit breaker khi thật sự cần.
- Quản lý cấu hình qua profile/environment/secrets; không hardcode mật khẩu/token hoặc môi trường.
- Ưu tiên dependency injection, không tạo service bằng `new` ở nhiều nơi; không đặt state request vào singleton bean.
- Test business rules ở unit level; integration test cho persistence, transaction và security khi cần.

<a id="java-conventions"></a>

### 4.1 Kiến trúc và convention Java của repository

- Giữ modular monolith, package gốc `vn.com.coreacademy`; chia module theo chức năng, trong module có controller/service/repository/entity/dto và package hỗ trợ khi cần. [ARCHITECTURE.md](docs/ARCHITECTURE.md) mô tả cấu trúc và luồng hiện tại.
- Controller xử lý HTTP, validation và caller; service xử lý use case, quyền, transaction; repository truy cập dữ liệu. Không trả JPA entity qua API. `common` không phụ thuộc module nghiệp vụ.
- Hạ tầng bảo mật toàn ứng dụng đặt tại `config` và `security`: SecurityConfig/JwtProperties quản lý wiring, SecurityContextUtils cung cấp các getter static cơ bản từ context đã xác thực, ApiSecurityErrorHandler xử lý lỗi HTTP. Package security dùng chung không query repository nghiệp vụ hoặc tự quyết định role/scope.
- Use case cần user hiện tại tự gọi `SecurityContextUtils.getUserId()`; controller chỉ chuyển dữ liệu request, không truyền actorId qua mọi layer. Helper chỉ có isAuthenticated/getUserId/getAuthorizationVersion, không chứa user profile, role/scope, entity, raw token hoặc gọi DB/Redis/HTTP. Không parse lại JWT hay lấy caller từ body/header của client. Policy/audit/helper nội bộ vẫn có thể nhận actorId tường minh; không nhầm actorId với userId của tài nguyên đang quản lý. Job/async cần context xác thực được thiết lập/truyền có chủ đích; thiếu context phải từ chối, không tự gán SYSTEM/anonymous.
- `common.constants` chỉ chứa hằng số kỹ thuật dùng chung; tận dụng HttpHeaders/MediaType có sẵn thay vì khai báo lại chuẩn HTTP. Mã role ở identity.constants.RoleCodes, JWT constants ở security; không gom tất cả vào GlobalConstants. Không tạo utils rỗng hoặc viết lại hàm thư viện chuẩn chỉ để đủ cấu trúc thư mục.
- Mã nghiệp vụ và giới hạn có cùng ý nghĩa phải có một nguồn định nghĩa, không lặp literal tại service/DTO/repository. Đặt trong `<module>.constants`, chia nhóm theo khái niệm; ví dụ LearningCodes.RevisionState khác CatalogStatus dù cùng có giá trị published. Các giới hạn cùng số nhưng khác nghiệp vụ phải có tên riêng. Không gom toàn hệ thống vào một GlobalConstants hoặc tạo constants cho mọi số 0/1, tên field/alias hay chuỗi chỉ có ý nghĩa cục bộ.
- DTO validation, allowlist tìm kiếm, service và thông báo giới hạn phải tham chiếu cùng định nghĩa. Annotation cần hằng số compile-time; không dùng ConfigurationProperties thay trực tiếp cho giá trị annotation. Nếu giới hạn đổi lúc vận hành thì kiểm tại service/validator phù hợp, tránh hai nguồn cấu hình độc lập. Tận dụng MediaType/HttpHeaders và constants thư viện chuẩn khi đã có.
- Query dùng named parameter để nhận mã nghiệp vụ từ constants; không ghép chuỗi SQL từ giá trị hoặc thay query text block bằng chuỗi nối. Với NativeSearchQuery, parameter chỉ có ở SELECT dùng bindProjection để không truyền sang COUNT. Giữ nguyên tên cột, alias, semantics và kiểm tra cả list/count với dữ liệu thực thi query.
- Timeout, lease, lịch poll và retry thay đổi theo môi trường đặt trong ConfigurationProperties của module, có đơn vị, mặc định tập trung và validation quan hệ thời gian. Không lặp @Value/default hoặc magic number ở từng service. Mã nghiệp vụ ổn định, giới hạn contract và chính sách hoàn thành không mặc định trở thành cấu hình vận hành.
- Khi gom constants/config phải giữ nguyên giá trị JSON/DB, regex validation, đơn vị, giới hạn và hành vi mặc định; đổi tên hằng số Java không đồng nghĩa được đổi contract/dữ liệu. Enum chỉ dùng khi đem lại kiểm soát kiểu/hành vi rõ ràng và đã rà mapping/serialization/query. SQL baseline và tài liệu là contract độc lập cần đối chiếu, không thay literal hàng loạt theo cùng chuỗi.
- Filter dùng chung ở common.web chỉ xử lý HTTP/context: RequestIdFilter chạy trước Spring Security, validate/tạo request ID, đặt response header/MDC và khôi phục trong finally, không đọc/log body. JWT đi qua Spring Security; policy nghiệp vụ nằm ở service. Không đăng ký cùng filter vừa Servlet component vừa thêm vào SecurityFilterChain khiến chạy lặp. Bổ sung access log/async propagation khi có use case, không mặc định cache body hoặc sao chép framework khác.
- Kho file lâu dài dùng AIStor/S3 qua module storage, không fallback local. `storage.service.ObjectStorage` chỉ xử lý SDK/object; `storage.web.ObjectDownloadHandler` quản lý Range/If-Range, HEAD, HTTP header và vòng đời stream. Controller kiểm tra input, gọi service nghiệp vụ lấy metadata đã kiểm quyền rồi ủy quyền download; không đặt copy stream/header helper tại controller hoặc gom vào common.utils.
- `storage.service.UploadStaging` quản lý file tạm/SHA-256 theo stream, trả StagedFile dùng try-with-resources; bên gọi đóng input. Module nghiệp vụ giữ signature/extension, giới hạn byte, metadata, quyền, key và cleanup object khi upload/transaction thất bại. Không chuyển policy vào storage, không tạo helper cho từng thao tác nhỏ hoặc public API nhận bucket/key từ client. Không nạp video toàn bộ vào RAM hoặc giữ DB transaction suốt stream. Hướng dẫn cấu hình/migration tại [AIStor](docs/operations/DEPLOY_UBUNTU.md#aistor-integration).
- Thành phần riêng giữ trong module. Chỉ đưa helper thuần, DTO, validation hoặc constants vào common khi thực sự dùng chung và không thuộc một nghiệp vụ; không gom service/phân quyền/query vào utils. Giá trị thay đổi theo môi trường thuộc configuration properties.
- Service dùng class cụ thể và constructor injection; không tạo cặp Service/ServiceImpl nếu chỉ có một implementation. Dependency final dùng Lombok `@RequiredArgsConstructor`; giữ constructor thủ công khi có xử lý đặc biệt.
- DTO ưu tiên record, không thêm Lombok để sinh lại accessor/constructor. PATCH cần phân biệt field bị bỏ qua với null có thể dùng class Lombok và setter đánh dấu field hiện diện; từ chối field ngoài whitelist.
- Entity dùng `@Getter`, `@Setter`, `@NoArgsConstructor` có chọn lọc; không dùng `@Data` hoặc tự sinh toString/equals/hashCode qua quan hệ JPA. Timestamp persistence quản lý không mở setter tùy tiện. Không log trực tiếp credential/token; giữ toString che secret của request/response/config có dữ liệu nhạy cảm.
- Mapping đặt trong `<module>.mapper`, dùng MapStruct với `CentralMapperConfig`: Spring component, constructor injection, unmapped target/type conversion báo lỗi, kiểm tra null. Khai báo `@Mapping`/`@Named` khi khác tên hoặc enum; ignore tại mapper cụ thể, không nới global policy để che lỗi.
- Mapping request tạo entity phải whitelist field được phép. Service vẫn kiểm tra quyền, membership, chuẩn hóa và xử lý password/hash/auth version/role; không tự động map payload vào các field nhạy cảm. Mapper không query hoặc gây side effect. Không thêm ModelMapper/BeanUtils song song.
- Lombok version theo Spring Boot BOM; giữ annotation processors Lombok, MapStruct và lombok-mapstruct-binding trong pom.xml. Không sửa/commit generated sources ở target.
- Module dùng service/DTO/adapter công khai của module khác. Riêng quản trị Identity/Organization có thể phối hợp repository trong cùng transaction để giữ integrity logic của 8 bảng; không suy ngoại lệ này thành quyền truy vấn chéo module tùy ý.
- Phiên bản Java/compiler/dependency lấy từ pom.xml; build/runtime container lấy từ Dockerfile. Không tự nâng phiên bản ngoài task. Hiện compiler target là 17, Docker JDK/JRE 21; không sửa source/config chỉ để khớp baseline cũ.

<a id="java-style"></a>

### 4.2 Import và format Java

Áp dụng thống nhất cho toàn bộ source Java trong src/main/java; package-info, entity/DTO và query tuân theo phần riêng tương ứng. Mỗi task thông thường chỉ chỉnh format trong phạm vi được giao; chỉ rà/refactor toàn source khi người dùng yêu cầu. Không đổi logic hoặc thứ tự mapping chỉ vì format.

#### Import và tên kiểu

- Dùng import ở đầu file rồi dùng tên ngắn: `private Instant createdAt`, `Instant.now()`, `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`.
- Không viết tên package đầy đủ trong field, tham số, annotation hoặc lời gọi khi có thể import. Ngoại lệ: hai kiểu trùng simple name và không thể biểu đạt rõ bằng import; chỉ giữ tên đầy đủ cho kiểu gây xung đột và giải thích khi cần.
- Không dùng wildcard import, kể cả static import. Bỏ import trùng/không dùng.
- Nhóm import theo thứ tự: java; javax/jakarta; thư viện khác và package dự án; static import. Mỗi nhóm sắp tăng dần theo tên đầy đủ, giữa các nhóm đúng một dòng trống.

#### Khoảng trắng và xuống dòng

- UTF-8, LF, bốn dấu cách mỗi cấp Java; không dùng tab. File kết thúc bằng newline, không có khoảng trắng cuối dòng.
- Một dòng trống giữa package/import/class, giữa các nhóm field và giữa các method. Không để nhiều dòng trống liên tiếp.
- Entity: mỗi field cùng các annotation của nó tạo một block; giữa hai block đúng một dòng trống. Không chèn dòng trống giữa annotation và field.
- Một field declaration trên một dòng; giữ thứ tự field hiện tại để dễ đối chiếu SQL.
- Annotation trên dòng riêng. Class entity giữ nhóm Lombok Getter/Setter/NoArgsConstructor trước nhóm Entity/Table như các entity hiện tại.
- Giới hạn dòng Java là 120 ký tự theo .editorconfig, tính cả indent. Chữ ký hàm, lời gọi hàm, biểu thức và annotation giữ một dòng nếu vừa giới hạn; chỉ ngắt khi bản một dòng vượt giới hạn. Không đổi giá trị chuỗi, SQL hoặc URL; không gộp nhiều statement hoặc thân block thành một dòng.
- Chữ ký hàm nhiều tham số: giữ tên hàm và các tham số đầu trên dòng khai báo khi còn chỗ. Nếu vượt 120 ký tự, nhóm nhiều tham số vừa dòng, ngắt sau dấu phẩy trước tham số làm vượt giới hạn; dòng tiếp theo căn theo tham số đầu. Nếu căn hàng làm dòng có annotation/kiểu dài vượt 120 ký tự, dùng continuation indent bốn dấu cách thay vì kéo toàn bộ dòng sang phải. Không mặc định xuống dòng ngay sau `(` hoặc mỗi tham số một dòng chỉ vì hàm có nhiều tham số. Tham số có annotation hoặc kiểu quá dài có thể cần dòng riêng; không áp dụng ngoại lệ đó cho toàn bộ danh sách. DTO record và text block query/Swagger vẫn theo quy tắc riêng ở mục tương ứng.
- Khi rà format, kiểm tra cả dòng vượt giới hạn và việc xuống dòng thừa; không kết luận đạt chỉ vì mọi dòng đều ngắn hơn 120 ký tự. Với chữ ký hàm đang sửa, gộp lại các tham số bị tách máy móc theo quy tắc trên; không đổi signature nghiệp vụ hoặc format lan sang file ngoài phạm vi.
- Mỗi cụm xử lý một mục đích; giữa hai cụm logic kế tiếp đúng một dòng trắng. Chia theo luồng nghiệp vụ như kiểm tra đầu vào/quyền, truy vấn, tải dữ liệu bổ sung và dựng kết quả; không chèn dòng trắng giữa từng statement hoặc dùng comment đánh số để thay cho cấu trúc rõ ràng. Quy tắc chữ ký hàm gọn không có nghĩa là dồn thân hàm liền nhau.
- Dấu mở khối cùng dòng khai báo. Control flow if/else/for/while/do dùng `{}` và thân khối xuống dòng, kể cả khi chỉ có một statement; không viết if (...) action; hoặc nhiều nhánh liên tiếp trên cùng dòng. Áp dụng cho code mới và cụm đang sửa, không format lan ra file ngoài phạm vi.

Ví dụ:

```java
import java.time.Instant;

import jakarta.persistence.Column;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Trích phần khai báo trong entity, không phải một file Java hoàn chỉnh.
@Column(name = "created_at", nullable = false)
private Instant createdAt = Instant.now();

@Column(name = "description", columnDefinition = "text")
@JdbcTypeCode(SqlTypes.LONGVARCHAR)
private String description;
```


Ví dụ chữ ký hàm vượt giới hạn một dòng, nhóm tham số theo chỗ còn lại:

```java
public PageResponse<UserSummary> list(String q, String roleCode, Long orgId, Long teamId, String status,
                                     int page, int size, String sort) {
```

#### Lombok và DTO

Entity dùng annotation Lombok có chọn lọc theo mục 4.1; không thêm Data/ToString/EqualsAndHashCode trong lúc format. DTO record giữ record; record nhiều thành phần có thể xuống dòng từng thành phần cho dễ đọc. Không thêm getter/setter thủ công để thay đổi cách hiển thị IDE.

#### Comment, Javadoc và mô tả Swagger

- Comment giải thích nghiệp vụ, ràng buộc và lý do thiết kế; không bắt buộc comment mọi hàm, getter/setter hoặc CRUD đơn giản. Ưu tiên tên rõ nghĩa và hàm dễ đọc trước khi bổ sung comment.
- Viết tiếng Việt rõ ràng, ngắn gọn, thống nhất thuật ngữ; giữ nguyên tên field, API, mã role/scope và định danh kỹ thuật. Không chứa credential, dữ liệu cá nhân thật hoặc thông tin nội bộ nhạy cảm.
- **Controller:** `@Operation.summary` là tên thao tác ngắn; `description` nêu mục đích và các lưu ý quan trọng cho FE như phạm vi quyền hoặc yêu cầu đăng nhập lại. Description ở controller dùng Java text block `"""`: một câu mục đích ngắn, ngay sau là bullet Markdown `- ` cho các lưu ý cần thiết; không nối chuỗi thành đoạn văn dài. Không chèn dòng trắng trong text block, kể cả giữa câu mở đầu và bullet hoặc trước dấu đóng; không thêm dòng trắng giữa annotation và method. Dấu mở/đóng text block nằm trên dòng riêng, nội dung thụt lề đồng nhất và mỗi dòng tối đa 120 ký tự. Text block tài liệu được xuống dòng theo cấu trúc này dù từng dòng còn ngắn. Chi tiết query/path đặt ở `@Parameter`, field và PATCH bỏ qua/null ở `@Schema`, kết quả/lỗi đặc thù ở `@ApiResponse`; quy ước chung chỉ mô tả một lần ở đầu Swagger. Không viết Javadoc lặp Swagger, ép mọi API có bullet hoặc mô tả hành vi chưa triển khai.
- **Service:** dùng Javadoc cho use case có quy tắc nghiệp vụ, nhiều bước hoặc tác động liên quan. Nêu mục đích, điều kiện quan trọng, dữ liệu bị thay đổi và tác động như thu hồi quyền/token. Chỉ mô tả transaction/rollback khi implementation thực sự bảo đảm; không cam kết atomicity giữa nhiều request riêng.
- **Policy/Security:** ưu tiên giải thích nguồn quyền, quan hệ role với scope, giới hạn dữ liệu và các nhánh kiểm tra dễ hiểu sai. Comment tại quyết định quan trọng cần nêu lý do kiểm tra/từ chối, không chỉ nhắc lại điều kiện trong code.
- **Repository:** query phức tạp, native SQL, recursive CTE hoặc locking cần giải thích phạm vi dữ liệu, ý nghĩa kết quả và lý do chọn cách truy vấn/khóa khi không thể suy ra dễ dàng. Nêu ràng buộc đồng thời quan trọng; không diễn giải từng dòng SQL hoặc khẳng định hiệu năng khi chưa đo.
- **Entity/DTO:** chỉ giải thích field/mapping có ý nghĩa đặc biệt, dữ liệu nguồn, đơn vị, giá trị hợp lệ hoặc nullable dễ nhầm. DTO dùng `@Schema` cho thông tin cần hiển thị trên Swagger, nhất là khác biệt giữa bỏ qua và gửi null trong PATCH; tránh lặp nguyên annotation validation.
- **Common/Utils/Config:** dùng Javadoc hoặc comment khi có điều kiện sử dụng, giới hạn, hành vi lỗi hoặc quyết định cấu hình không hiển nhiên. Ví dụ helper đọc security context cần nêu điều kiện đã xác thực và hành vi khi thiếu context.
- Javadoc bắt đầu bằng một câu mô tả mục đích; bổ sung đoạn ngắn cho quy tắc/tác động quan trọng. Chỉ dùng `@param`, `@return`, `@throws` khi bổ sung thông tin có ích, không lặp tên tham số hoặc kiểu trả về. Comment trong thân hàm đặt sát đoạn cần giải thích, tuân thủ giới hạn 120 ký tự mỗi dòng.
- Khi sửa logic phải cập nhật hoặc xóa comment không còn đúng trong cùng thay đổi. Không để code bị comment, lịch sử sửa lỗi, tên người sửa hoặc ngày sửa trong code; Git quản lý lịch sử. TODO chỉ giữ khi có lý do và điều kiện xử lý rõ ràng, dẫn issue nếu có.
- Áp dụng cho code mới hoặc phần đang sửa; không thêm comment hàng loạt ngoài phạm vi task. Quy tắc chi tiết theo mục này, không sao chép sang AGENTS.md/CLAUDE.md hoặc tạo file comment guideline riêng.

Ví dụ description tại controller:

```java
@Operation(
    summary = "Cập nhật người dùng",
    description = """
        Cập nhật hồ sơ và tổ chức, nhóm trong phạm vi quản lý.
        - Đổi tổ chức/nhóm sẽ thu hồi phân quyền khác OWN và yêu cầu đăng nhập lại.
        - Không cho phép tự chuyển tổ chức hoặc nhóm.
        """
)
```

Ví dụ comment tại quyết định nghiệp vụ:

```java
// Kiểm tra cả phạm vi nguồn và đích để tránh chuyển người dùng ngoài quyền quản lý.
```

Ví dụ mô tả field PATCH trên Swagger:

```java
@Schema(description = "ID nhóm. Bỏ qua để giữ nguyên; gửi null để bỏ gán nhóm.")
private Long teamId;
```

#### IDE và kiểm tra

[.editorconfig](.editorconfig) đặt các lựa chọn cơ bản và hướng dẫn IntelliJ dùng single-class imports. Bật EditorConfig trong IDE, dùng Optimize Imports và Reformat Code cho phần đang sửa. Các IDE khác có thể không hỗ trợ khóa ij_java_*; vẫn phải theo quy tắc import ở đây.

EditorConfig không phải formatter/CI checker và không tự thực thi mọi quy tắc. Chưa thêm plugin format vào Maven/CI trong thay đổi này. Khi nhóm chọn formatter tự động, cấu hình formatter phải khớp chuẩn chung và commit cùng hướng dẫn chạy.

Kiểm chứng theo mục 10; không tạo test mới chỉ để kiểm tra khoảng trắng.

## 5. Frontend — Angular / TypeScript

> Chỉ áp dụng nếu repository dùng Angular; theo version và kiến trúc thực tế, không tự ý chuyển toàn bộ state management hoặc component style.

- Component tập trung trình bày và tương tác; business logic có thể tái sử dụng đặt tại service/facade phù hợp.
- Dùng type rõ ràng, hạn chế `any`, non-null assertion không có kiểm chứng và ép kiểu để che lỗi.
- Không gọi API lặp do lifecycle/subscription; dùng cơ chế hủy subscription phù hợp (`async` pipe, `takeUntilDestroyed` nếu phiên bản hỗ trợ).
- Cân nhắc `OnPush`, `trackBy`/`track`, pagination/virtual scrolling với danh sách lớn khi phù hợp; tránh function tính toán nặng trong template.
- Giữ một nguồn sự thật cho state; không sao chép state dẫn tới dữ liệu lệch nhau.
- Hiển thị rõ loading/empty/error/success; giữ tính nhất quán của UI và khả năng truy cập (keyboard, label, contrast).
- Frontend chỉ hỗ trợ ẩn/hiện theo permission; **backend phải luôn xác thực và kiểm tra quyền lại**.
- Không lưu thông tin bí mật vào frontend bundle; xử lý token/session theo thiết kế bảo mật dự án.
- Bảo vệ XSS: tránh HTML không tin cậy và bypass sanitizer không có kiểm soát.

## 6. Database — PostgreSQL / SQL

> Điều chỉnh theo DBMS thực tế. Nếu dự án không dùng PostgreSQL, không sao chép máy móc cú pháp bên dưới.

- Thiết kế bảng/cột theo nghiệp vụ và naming đã thống nhất; dùng kiểu dữ liệu phù hợp, constraints và index có chủ đích.
- **Không tự ý thêm foreign key/unique constraint** nếu dự án đã quy định quản lý quan hệ tại application layer; khi đó phải bổ sung validation và kiểm tra tính toàn vẹn tương ứng ở ứng dụng/job đối soát.
- Không `SELECT *` cho API production nếu chỉ cần một số trường; phân trang ở DB và dùng index phục vụ filter/join/sort thường gặp.
- Không tạo index theo cảm tính: xem cardinality, selectivity, write overhead và `EXPLAIN (ANALYZE, BUFFERS)` trên dữ liệu đại diện khi có thể.
- Dùng prepared/parameterized queries; tránh nối string SQL từ dữ liệu người dùng.
- Tránh N+1, join nhân bản bản ghi, count sai do quan hệ một-nhiều; xác định rõ grain của mỗi báo cáo.
- Migration/SQL upgrade cần định danh và kế hoạch tương thích, backup/rollback theo cơ chế hiện có; không tự chuyển sang Flyway hoặc sửa dữ liệu production bằng script chưa kiểm tra.
- Phân biệt transaction ID, event/attempt ID và business ID trước khi deduplicate.
- Không dùng `OFFSET` sâu cho dữ liệu cực lớn nếu keyset pagination phù hợp hơn.

### 6.1 Query trong source Java

- SQL chia theo cụm điều kiện liên quan để dễ đối chiếu nghiệp vụ; giữ SELECT/FROM/JOIN/WHERE/EXISTS và nhóm AND/OR có indent rõ ràng. Chỉ tách helper cho cụm phức tạp như role/scope; không tách mỗi điều kiện thành một hàm hoặc chia nhỏ query qua nhiều file làm mất khả năng đọc toàn bộ logic. Text block SQL vẫn không có dòng trắng thừa bên trong; phân tách các cụm dựng query Java bằng một dòng trắng.

- Search nhiều filter đặt tại `<module>.repository.custom` theo Spring Data repository fragment: interface `<Entity>RepositoryCustom` và implementation cùng tên thêm Impl. Filter bất biến có thể là record lồng trong interface; projection riêng tại repository.projection khi cần read model. Không dùng Specification/JpaSpecificationExecutor hoặc dựng Criteria/SQL trong service. Query đơn giản một vài điều kiện cố định tiếp tục dùng derived query/@Query, không tạo custom fragment chỉ cho đủ cấu trúc.
- Service kiểm tra input/quyền và xác định scope đáng tin cậy; custom repository áp dụng scope trước phân trang và count. List/count dùng chung FROM/WHERE/parameters, role filter ưu tiên EXISTS để không nhân dòng. Scope hoặc danh sách ID lọc rỗng phải trả rỗng, không được bỏ điều kiện làm rộng quyền. Không bind role/scope từ request thay cho phạm vi do policy tính.
- NativeSearchQuery ở common.persistence chỉ chia sẻ ghép điều kiện tĩnh, bind parameter, LIKE literal, sort whitelist và thực thi page/count; không chứa tên bảng hoặc policy nghiệp vụ. Tạo helper riêng cho mỗi lượt search, không giữ builder mutable trên bean. Mệnh đề SQL/cột sort chỉ lấy từ source/whitelist; mọi giá trị client phải bind. Ghép fragment động là ngoại lệ có chủ đích cho rule query text block; các khối SELECT/FROM/EXISTS nhiều dòng vẫn dùng text block.
- Chỉ tải cột cần cho read model, không tải credential để dựng danh sách. Batch nhãn và grant cho cả trang, tránh query theo từng user/grant. Có thể bỏ count nếu PageableExecutionUtils suy ra chính xác từ page; trang rỗng ở offset lớn vẫn phải có tổng đúng. Kiểm tra số query và SQL thực thi; hiệu năng PostgreSQL cần đo query plan/dữ liệu đại diện, không mặc định native nhanh hơn JPA hoặc tự thêm index chưa được đánh giá.

- Query tĩnh trong `@Query`, `EntityManager.createQuery` hoặc `createNativeQuery` dùng Java text block `"""`, không nối nhiều chuỗi bằng `+`. Xuống dòng theo các mệnh đề `select/from/join/where/group by/order by`; điều kiện `and/or` và query con thụt lề nhất quán, mỗi dòng tối đa 120 ký tự. Không có dòng trắng thừa trước nội dung, giữa các dòng hoặc trước dấu đóng. Text block query được xuống dòng theo cấu trúc này dù từng dòng còn ngắn.
- Với HQL trên Hibernate hiện tại, dùng enum literal ngắn khi kiểu enum suy ra rõ từ field, ví dụ `a.user.status = ACTIVE` và `a.scopeType = GLOBAL`. Không ghi cả package enum và không đổi enum thành chuỗi `'GLOBAL'` chỉ để query ngắn. Field String như `role.code` hoặc literal trong native SQL vẫn dùng giá trị chuỗi theo mapping thực tế.
- Nếu giá trị thay đổi theo lời gọi hoặc enum không suy ra rõ, dùng named parameter có kiểu enum Java (`ScopeType`, `UserStatus`), không truyền chuỗi thay enum. Không thêm tham số luôn cố định chỉ để tránh enum literal. Không thay đổi signature repository nếu không cần cho nghiệp vụ.
- Enum literal ngắn là khả năng của HQL, không phải cú pháp JPQL portable. Khi đổi JPA provider hoặc bật strict JPQL compliance, phải rà lại query; dùng enum parameter hoặc tên đầy đủ nếu môi trường đó yêu cầu. Không nới cấu hình compliance chỉ để query chạy.
- Khi chuyển format query, giữ nguyên alias, điều kiện, join/fetch, thứ tự, parameter, `nativeQuery` và `@Modifying`; không tự tối ưu hoặc thay logic. Build chỉ kiểm tra compile; cần chạy kiểm tra có khởi tạo repository và thực thi query liên quan. Native SQL đặc thù PostgreSQL cần được xác minh trên PostgreSQL khi có môi trường được phép, không coi H2 là bằng chứng tương đương.

Ví dụ HQL:

```java
@Query("""
    select a from RoleAssignment a
    join fetch a.role
    join fetch a.user
    where a.active = true
      and a.user.status = ACTIVE
      and a.scopeType = GLOBAL
    """)
```

## 7. Batch, đồng bộ và báo cáo số liệu

> Chỉ áp dụng khi task có batch/đồng bộ/báo cáo tương ứng. Các ví dụ giao dịch và thưởng dưới đây không phải đặc tả LMS hoặc yêu cầu tạo thêm module; nghiệp vụ thực tế phải được xác nhận theo task.

- Xác định rõ **grain**: 1 dòng là giao dịch gốc, lượt xác nhận, giao dịch theo ngày, hay kết quả theo user/đơn vị; không cộng lẫn các grain.
- Phân biệt `transaction_date` (ngày phát sinh), `updated_at` (ngày thay đổi trạng thái), `processed_at` (ngày job xử lý) và `snapshot_as_of` (thời điểm chốt ảnh dữ liệu).
- Job phải **idempotent**: rerun/retry không được cộng trùng. Ưu tiên upsert theo khóa nghiệp vụ ổn định hoặc tính lại partition/ngày bị ảnh hưởng một cách nguyên tử.
- Theo dõi watermark/checkpoint, số records đọc/xử lý/lỗi, duration và trạng thái từng batch; cập nhật checkpoint **sau khi** dữ liệu được commit an toàn.
- Nếu trạng thái giao dịch T thay đổi ở T+N, cơ chế đồng bộ cần cập nhật lại số liệu **ngày phát sinh T** khi báo cáo dùng trạng thái mới nhất.
- Trường hợp `PARTIAL_SUCCESS → SUCCESS` và cả hai đều đủ điều kiện: tổng giao dịch không tăng lần hai; chỉ điều chỉnh phân loại nếu có.
- Quy tắc chốt kỳ/khóa số liệu thưởng là nghiệp vụ riêng: không tự ý sửa bảng xếp hạng đã chốt; ghi nhận adjustment và audit trail theo phê duyệt.
- Xếp hạng giao dịch viên theo cá nhân: tổng theo định danh nhân sự ổn định trong phạm vi xét thưởng; đánh giá đơn vị: ghi nhận theo chi nhánh/PGD tại thời điểm phát sinh. Không trộn hai quy tắc.
- Tránh quét lại toàn bộ dữ liệu lịch sử hằng ngày khi có thể đồng bộ biến động và đối soát theo khoảng thời gian phù hợp.
- Không giả định status, giao dịch hợp lệ hoặc chính sách thưởng nếu chưa được nghiệp vụ xác nhận.

## 8. Security, logging và xử lý lỗi

### Security

- Kiểm tra authentication, authorization và phạm vi dữ liệu tại backend, kể cả khi FE đã ẩn action.
- Validate/sanitize input đúng ngữ cảnh; dùng parameterized query; giới hạn upload, request size, pagination và rate theo nhu cầu.
- Không ghi log/token/secret/password/PII không cần thiết; không đưa secret vào git, file guideline hoặc prompt output.
- Áp dụng least privilege cho DB/service account; bảo vệ endpoint quản trị và thao tác thay đổi trạng thái.

### Error handling & observability

- Không `catch (Exception)` rồi bỏ qua hoặc trả thành công giả.
- Log có ngữ cảnh (request/correlation ID, operation, business key nếu an toàn), mức log hợp lý và không spam theo từng record lớn.
- Trả lỗi API nhất quán với mã lỗi có thể xử lý; che chi tiết nội bộ trước client.
- Bổ sung metric cho latency, error rate, batch duration, failed records và DB query khi có cơ sở hạ tầng hỗ trợ.

## 9. Hiệu năng và độ ổn định

- Đặt mục tiêu theo khối lượng thực tế: số giao dịch, throughput, concurrent users, tần suất job, timeout, SLA.
- Trước khi tối ưu, xác định bottleneck qua query plan, profiler, tracing, log hoặc benchmark; tránh tối ưu hóa đoán mò.
- Kiểm soát độ phức tạp thuật toán, số round-trip DB/API, allocation memory, payload size và render frequency.
- Cache chỉ khi có yêu cầu thực tế và chiến lược TTL/invalidation; không cache dữ liệu phân quyền hoặc số liệu thưởng mà thiếu đảm bảo nhất quán.
- Với job lớn, dùng batching/chunking và checkpoint; tính toán memory, lock contention và retry budget.
- Ưu tiên kết quả đúng dưới đồng thời (race condition, duplicate delivery, lost update) hơn benchmark nhanh nhưng sai dữ liệu.

## 10. Testing và tiêu chuẩn hoàn thành

<a id="verification"></a>

Quy tắc kiểm chứng tại repository:

- Với thay đổi Java/dependency/annotation processor, chạy `mvn clean verify` từ root khi môi trường cho phép. Không thêm dependency/framework test chỉ để đáp ứng checklist. Với thay đổi chỉ tài liệu, kiểm tra diff, liên kết và sự nhất quán; không cần build Java chỉ để xác minh Markdown.
- **Không tự tạo hoặc sinh thêm file test vào source repository; chỉ thêm khi người dùng yêu cầu rõ ràng.** Ưu tiên test hiện có và kiểm tra phù hợp; nếu không có test, nói rõ Maven không chạy test. Build thành công không đồng nghĩa test pass. Danh sách dưới đây chỉ định loại kiểm tra cần cân nhắc, không phải lệnh sinh thêm test.
- Kiểm tra API thật chỉ dùng tài khoản/dữ liệu đã được cho phép; không tự seed, reset password hoặc cấp quyền để làm test pass. Postman CLI đã có và có thể dùng khi phù hợp.
- Khi đổi build/filesystem runtime, kiểm tra Docker/CI liên quan; chưa chạy Docker thật phải nêu rõ. Không khẳng định kiểm chứng chưa thực hiện.

Chọn các kiểm tra liên quan đến thay đổi:

- **Unit tests:** nghiệp vụ và edge cases.
- **Integration tests:** query, mapping, transaction, security và integration contract nếu liên quan.
- **Regression tests:** luồng đã hoạt động và bị tác động.
- **Batch/report tests:** rerun, retry, duplicate event, late status update, partial success, đổi chi nhánh/PGD, kỳ đã chốt.
- **Performance checks:** số queries, pagination, index, memory và tải representative đối với màn báo cáo/job lớn.
- **Security checks:** truy cập không đăng nhập, thiếu quyền, truy cập chéo phạm vi dữ liệu, input bất thường.

### Checklist trước khi bàn giao

- [ ] Đáp ứng đúng điều kiện nghiệm thu, xử lý edge cases quan trọng.
- [ ] Không thay đổi nghiệp vụ/API/schema ngoài phạm vi được duyệt.
- [ ] Code rõ nghĩa, ít duplication, không abstraction dư thừa.
- [ ] Không có query/API call bất hợp lý, thiếu pagination hoặc retry vô hạn.
- [ ] Kiểm tra quyền tại backend; không rò rỉ secrets/dữ liệu nhạy cảm.
- [ ] Có xử lý lỗi, logging vừa đủ, transaction và tính toàn vẹn dữ liệu.
- [ ] Đã chạy các bước kiểm chứng khả thi; công khai bước chưa chạy.
- [ ] Có ghi chú migration/deploy/rollback khi phát sinh thay đổi liên quan.

## 11. Anti-pattern cần tránh

| Không nên | Nên làm |
|---|---|
| Controller chứa toàn bộ SQL và business logic | Controller mỏng, service rõ nhiệm vụ, persistence có ranh giới |
| Tạo interface/helper cho mỗi class dù chỉ có một cách dùng | Tạo abstraction khi cần thay thế, test, hoặc tái sử dụng thực |
| Mỗi lần lặp gọi DB/API riêng | Gom query/batch call hoặc thiết kế truy vấn phù hợp |
| Lưu kết quả báo cáo bằng phép cộng mỗi lần retry | Upsert/deduplicate/recompute có tính idempotent |
| Lấy đơn vị công tác hiện tại để gán lại giao dịch quá khứ | Lưu/tra cứu đơn vị tại thời điểm giao dịch |
| Nuốt exception, trả empty/success để che lỗi | Phân loại lỗi và thông báo/log đúng nơi |
| Refactor toàn module trong một bugfix nhỏ | Khoanh vùng nguyên nhân và chỉnh đúng phạm vi |
| Viết comment giải thích cú pháp hiển nhiên | Comment diễn giải quy tắc nghiệp vụ và trade-off |

## 12. Hướng dẫn dành cho AI Coding Agent

Trước mỗi yêu cầu:

1. Đọc file này và các hướng dẫn có hiệu lực như `AGENTS.md`, README và ADR liên quan.
2. Khảo sát code thực tế rồi mới lựa chọn kỹ thuật; **không áp đặt** giả định từ file này lên repository nếu không khớp.
3. Nêu kế hoạch ngắn với task vừa/lớn; task nhỏ thực hiện trực tiếp sau khi hiểu yêu cầu.
4. Không tự ý thêm chức năng, đổi kiến trúc, dependency, DB schema hoặc refactor ngoài phạm vi.
5. Nếu thiếu quy tắc nghiệp vụ quan trọng, hỏi một câu tập trung hoặc ghi rõ blocker; không tự bịa nghiệp vụ.
6. Sau sửa, kiểm tra những gì có thể và báo cáo minh bạch tình trạng build/test.

**Mẫu báo cáo cuối task:**

```text
Đã thực hiện:
- ...

Các file thay đổi:
- ...

Kiểm chứng:
- [PASS/FAIL/NOT RUN] ...

Lưu ý / rủi ro còn lại:
- ...
```

---

**Nguyên tắc xuyên suốt:** Không cố tạo code nhiều hay kiến trúc cầu kỳ. Hãy tạo **giải pháp nhỏ nhất đủ đúng, dễ hiểu, đo lường được khi cần, và an toàn để người khác tiếp tục bảo trì**.


<a id="project-rules"></a>

## 13. Ràng buộc dự án và tài liệu theo phạm vi

### 13.1 Workspace, cấu hình và phạm vi thao tác

- Đọc code/config thực tế, dùng rg để tìm file/nội dung; đọc hướng dẫn thư mục con liên quan. Giữ thay đổi của người dùng, không reset/clean/checkout/ghi đè để làm sạch workspace.
- Hoàn thành việc đã được giao; không tự commit, push, deploy hoặc thay đổi dữ liệu/schema trên server khi chưa được yêu cầu. Khi được yêu cầu, chỉ thực hiện đúng phạm vi đã cho phép.
- Local dùng một `.env` ở thư mục chạy, application.yml tự import; không tạo thêm env/profile local khi chưa cần. Cấu hình server/CI độc lập; không ghi đè bằng secret local.
- Không in/log/commit password, hash, JWT, signing secret hay private key. Không tin rằng .gitignore đã bảo vệ secret; kiểm tra file và staging trước commit.
- Logging qua SLF4J + Log4j2 theo [LOGGING.md](docs/operations/LOGGING.md); không log body/Authorization header của API xác thực.

### 13.2 API, database và Identity

- Chính sách HTTP cache chung do config.SecurityConfig quản lý qua cache-control header writer của Spring Security (no-cache, no-store, max-age=0, must-revalidate, kèm Pragma/Expires). Controller và exception handler không lặp noStore; chỉ endpoint có yêu cầu cache khác mới khai báo override rõ ràng và kiểm tra header thực tế.
- Business API dưới `/api/v1`, nhận DTO trực tiếp; không có BaseRequest/data wrapper. Response `BaseResponse<T>`; lỗi MVC/Security dùng cùng envelope, giữ HTTP status và request ID X-Request-Id/MDC. Không bọc Swagger/health/file/stream. Đổi contract phải cập nhật Swagger/tài liệu theo phạm vi.
- Kết quả nghiệp vụ độc lập HTTP: validation và lỗi logic (trùng dữ liệu, membership/scope không hợp lệ, dữ liệu nghiệp vụ không tìm thấy) trả HTTP 200 với status=ERROR. Thành công trả status=SUCCESS, tạo mới có thể dùng 201. Sai thông tin đăng nhập/token trả 401, thiếu quyền 403, lỗi hệ thống 500; lỗi giao thức như JSON không đọc được, URL/method/media type sai giữ HTTP tương ứng. Không đổi lỗi hệ thống thành lỗi nghiệp vụ để che lỗi. FE luôn kiểm tra status/messageCode; BusinessException vẫn rollback transaction khi HTTP 200.
<a id="response-value-format"></a>

- Format giá trị trong BaseResponse: `status` chỉ dùng `SUCCESS` hoặc `ERROR` (IN HOA); không dùng success/error hoặc nhiều cách viết song song. Quy tắc này áp dụng cho trạng thái kết quả response, không đổi các trạng thái nghiệp vụ như user.status đang dùng active/inactive/locked.
- `messageCode` theo dạng `<PREFIX>-<REASON>`: prefix và reason IN HOA, các từ trong reason nối bằng `_`, không chứa HTTP status. Prefix theo phạm vi: CORE cho lỗi/kết quả chung, IDENTITY cho tài khoản và phân quyền, ORGANIZATION cho tổ chức và nhóm. Ví dụ: CORE-SUCCESS, CORE-VALIDATION_FAILED, IDENTITY-CREDENTIALS_INVALID, ORGANIZATION-TEAM_IN_USE. Mỗi mã có ý nghĩa ổn định; không tạo mã riêng cho từng field hoặc dùng tên endpoint/tầng Java làm mã. Mã hiện hữu chưa chuyển đổi phải giữ tương thích; chỉ đổi cùng task chuẩn hóa contract, cập nhật Swagger/tài liệu và thông báo FE.
- `errors[].code` dùng UPPER_SNAKE_CASE, ví dụ REQUIRED, INVALID_FORMAT, OUT_OF_RANGE; chi tiết lỗi trường dùng field path của DTO, kể cả phần tử nested. Không phản chiếu tên exception/annotation Java thành contract mới. Tên field JSON vẫn camelCase như responseId, messageCode, messageTitle.
- `message` và `messageTitle` viết tiếng Việt tự nhiên, rõ ràng, thống nhất thuật ngữ; giữ nguyên tên kỹ thuật khi cần. message mô tả kết quả hoặc vấn đề và gợi ý xử lý khi hữu ích; messageTitle là tiêu đề ngắn, không lặp cả câu message. Một messageCode có message/title mặc định thống nhất. Khi có đúng một errors item, message ngoài dùng cùng thông báo cụ thể của item đó; khi nhiều lỗi, giữ message mặc định và trả chi tiết trong errors. Lỗi nghiệp vụ gắn được field (trùng định danh/mã, membership, role/scope) phải trả field path đúng DTO, kể cả initialRoleGrants[index].field. Không ghép dữ liệu nhạy cảm hoặc nội dung exception vào thông báo. FE phân loại bằng status/messageCode, không so khớp chuỗi message/messageTitle. Ví dụ thành công: “Thao tác thành công.” / “Thành công”; validation: “Dữ liệu đầu vào không hợp lệ.” / “Thông tin chưa hợp lệ”.
- Identity compact có 8 bảng schema `learning_hub`; LOCAL password ở `users.password_hash`. User ID là chuỗi giữ số 0 đầu; login mã cán bộ hoặc username, từ chối alias mơ hồ. SSO không dùng password local.
- Quan hệ database là logic, không tự thêm FOREIGN KEY vật lý; mapping JPA dùng NO_CONSTRAINT khi cần. Service kiểm tra tham chiếu, team thuộc org và chu trình cây. Không suy BRN/BRCD/CRA thành cây tổ chức.
- Cụm Identity chỉ có một nguồn SQL khởi tạo `docs/sql/learning_hub_identity.sql`: đủ 8 bảng, comment/index và seed demo đã được người dùng yêu cầu. Không tách thêm file upgrade, seed hoặc gán root cho baseline demo; cập nhật file chuẩn và SQL README cùng thay đổi. Giữ ddl-auto=validate/Flyway off; không phục hồi migration legacy hoặc tự chạy SQL khi startup. File chỉ chạy một lần trên database/schema mới/tạo lại, không tự DROP và không dùng nâng cấp dữ liệu thật. Kế hoạch thay đổi DB đang có dữ liệu phải được review theo phạm vi riêng.
- Role và scope phải thuộc cùng grant. Backend lấy org/team/owner từ dữ liệu thật; ORG gồm node được gán và cây con active theo [Contract Identity](docs/api/IDENTITY.md). Quyền màn hình không cấp quyền CREATE/UPDATE/DELETE.
- SUPER_ADMIN GLOBAL là quản trị gốc và có quyền nghiệp vụ toàn hệ thống; chỉ SUPER_ADMIN được cấp/thu hồi ADMIN/SUPER_ADMIN hoặc quản lý tài khoản đang mang vai trò quản trị. ADMIN GLOBAL/ORG quản lý user thường theo scope, không tự mở rộng quyền qua role-grant, initialRoleGrants, chuyển tổ chức/nhóm hoặc đổi trạng thái. Gợi ý editable/revocable phải khớp policy; /auth/me vẫn là đường tự sửa hồ sơ.
- Bảo vệ SUPER_ADMIN GLOBAL active cuối cùng trong transaction có IdentityWriteLock. Bootstrap chỉ tạo tài khoản gốc mới khi cung cấp đủ cấu hình và chưa có root active; bắt buộc đổi mật khẩu, audit cùng transaction. Không tự reset, nâng quyền hoặc khôi phục grant của user đã tồn tại khi restart. Seed SQL khởi tạo demo đã có root; không cần cấu hình bootstrap khi dùng bộ seed. Tài khoản/mật khẩu công khai trong seed là dữ liệu tổng hợp được yêu cầu; riêng 7 tài khoản demo dùng must_change_password=false, user tạo qua API/bootstrap vẫn phải đổi mật khẩu tạm; không đưa credential thật vào SQL hoặc tài liệu.
- Mật khẩu LOCAL tạo mới/đổi/bootstrap tối thiểu 6 ký tự, tối đa 72 byte UTF-8 do BCrypt; không thêm yêu cầu độ phức tạp hoặc cắt/trim mật khẩu. JWT subject là mã cán bộ; backend kiểm tra status/auth_ver. Logout thu hồi mọi phiên; thay đổi role/status/membership theo contract và audit trong transaction.
- Timestamp theo schema: create_time DB default, update_time do JPA lifecycle; Role/Function không có timestamp, Team chỉ create_time, RoleFunction dùng created_at, audit details JSONB. Đối chiếu SQL/entity thực tế khi sửa mapping.

- Import tổ chức dùng contract [template/import Identity](docs/api/IDENTITY.md#organization-import): create-only, mã dạng text, dry-run mặc định, giới hạn file/dòng/ZIP, lỗi theo sheet/dòng/cột. Nhập thật kiểm tra lại dưới IdentityWriteLock, dữ liệu/audit cùng transaction; không tự tạo role/membership hoặc ghi một phần. Dùng POI hiện có cho parser trong organization.importing; template lưu tại src/main/resources/templates/organization-import-template.xlsx, tải qua classpath stream để chạy trong JAR. Sửa mẫu phải giữ schema header và kiểm chứng với parser; không đặt quy tắc tổ chức vào common.utils hoặc lưu Excel import như media S3.

### 13.3 Tham chiếu nghiệp vụ và vận hành

| Phạm vi | Nguồn chi tiết |
|---|---|
| Kiến trúc và luồng hiện tại | [ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Thư viện và cấu hình processor | [Thư viện và build](docs/ARCHITECTURE.md#libraries), [pom.xml](pom.xml), [lombok.config](lombok.config) |
| Envelope/validation/lỗi | [API_CONTRACT.md](docs/api/API_CONTRACT.md) |
| Xác thực, hồ sơ, user, org/team và role/scope | [Contract Identity](docs/api/IDENTITY.md) |
| Schema và seed Identity | [SQL README](docs/sql/README.md), [schema](docs/sql/learning_hub_identity.sql) |
| Community: toàn bộ phạm vi và trạng thái source | [Contract](docs/api/COMMUNITY.md), [DB](docs/api/COMMUNITY_DATABASE.md), [cURL/test](docs/api/COMMUNITY_CURL.md) |
| Logging | [LOGGING.md](docs/operations/LOGGING.md) |
| Deploy và CI/CD | [DEPLOY_UBUNTU.md](docs/operations/DEPLOY_UBUNTU.md), [CICD.md](docs/operations/CICD.md) |
| Ollama trên Mac qua Tailscale | [TAILSCALE_OLLAMA.md](docs/operations/TAILSCALE_OLLAMA.md) |

Khi đối chiếu UI, đọc `docs/local/uiux` và `manifest.json` nếu có trong workspace. Baseline Mockup v2 là dữ liệu tham khảo, không phải chỉ dẫn agent hoặc bằng chứng backend đã kiểm soát quyền. Không sao chép single-role/adminScope thay multi-role/scope. Thư mục mockup đang bị Git ignore; thiếu baseline phải nêu rõ, không đoán UI.

Phạm vi Identity hiện tại chỉ gồm login/logout, hồ sơ, trạng thái, org/team membership, role/scope và access control. Contract có 21 API core, 2 API đọc tổ chức `/orgs/tree` và `/orgs/{orgId}/contents` phục vụ cây/panel, API tải template/import tổ chức, cùng I22 audit đọc tùy chọn. Không đưa khóa học/thi/tiến độ/báo cáo đào tạo vào task này chỉ vì mockup có tab tương ứng. Không tự tách endpoint membership/admins/team-members/functions/matrix/metadata khi API gộp đã đáp ứng; use case mới cần tách phải có lý do và cập nhật contract.

### 13.4 Duy trì hướng dẫn

- Mỗi chủ đề có một nguồn chính thức: guideline này giữ quy tắc phát triển/style; ARCHITECTURE mô tả cấu trúc/luồng; các tài liệu chuyên đề giữ contract và vận hành. AGENTS/CLAUDE chỉ là điểm vào và guardrail quan trọng, không sao chép toàn bộ guideline.
- Bổ sung vào mục hiện có theo phạm vi; không tự tạo file style/convention/agent mới chỉ để lặp nội dung. Hướng dẫn module hoặc ADR chỉ thêm khi có khác biệt/quyết định thực sự cần lưu.
- Cập nhật quy tắc đã thống nhất cùng thay đổi code; đánh dấu đề xuất chưa chốt. Không lưu hội thoại, credential hoặc log tạm vào tài liệu chuẩn.
- Tài liệu không chứng minh implementation/test đã có. Tự động hóa kiểm tra khi có yêu cầu phù hợp; tài liệu không thay CI.
- Commit tài liệu dùng chung theo phạm vi: API tại docs/api, vận hành tại docs/operations, SQL tại docs/sql. Nhật ký kiểm tra, ghi chú và bản nháp cá nhân để docs/local (Git ignore); mockup để docs/uiux. Chỉ chuyển quyết định đã chốt từ ghi chú local vào nguồn tài liệu chính, không commit cả lịch sử trao đổi.
