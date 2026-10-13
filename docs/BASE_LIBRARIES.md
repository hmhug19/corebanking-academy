# Đánh giá thư viện cho base backend

Baseline: JDK 21 LTS, Spring Boot 3.5.16 GA, package `vn.com.coreacademy`. Giữ version Spring/Hibernate/Jackson/Mockito/PostgreSQL do Spring Boot quản lý; thư viện ngoài BOM cần pin version riêng và kiểm tra tương thích.

## Đã bổ sung và áp dụng

| Thành phần | Cấu hình | Giá trị thực tế |
|---|---|---|
| MapStruct 1.6.3 | Dependency API, processor riêng trong Maven compiler | Sinh mapper tại compile, AuthService dùng UserMapper thay mapping thủ công |
| Spring Boot configuration processor | Optional dependency, processor theo version parent | Sinh metadata cho JwtProperties, hỗ trợ IDE gợi ý tên cấu hình |

MapStruct cấu hình chung tại `common.mapper.CentralMapperConfig`: Spring component, constructor injection, báo lỗi khi thiếu target field hoặc conversion mất dữ liệu, kiểm tra null trước khi map. Mapper nghiệp vụ nằm trong `<module>.mapper`, dùng `@Mapper(config = CentralMapperConfig.class)`. [Tài liệu MapStruct](https://mapstruct.org/documentation/stable/reference/html/).

`UserMapper` chuyển UserAccount sang UserInfo; status giữ giá trị chữ thường bằng named mapping. UserCredential không nằm trong mapper hồ sơ. Không tự tạo mapping DTO → entity cho request tạo/sửa user: trường ID/status/auth version/role và credential phải được xử lý theo use case và quyền, tránh ghi đè qua payload.

Processor chỉ phục vụ build; `mapstruct-processor` không là runtime dependency. Implementation sinh ra nằm ở `target/generated-sources/annotations`, không commit hoặc sửa trực tiếp. Không thêm ModelMapper/BeanUtils song song với MapStruct. [Cấu hình processor của Spring Boot](https://docs.spring.io/spring-boot/3.5/specification/configuration-metadata/annotation-processor.html).

## Thư viện đang có và nên giữ

| Nhóm | Thành phần | Đánh giá |
|---|---|---|
| REST | Spring MVC + Jackson | Đủ cho JSON API hiện tại; chưa cần WebFlux |
| Validation | Bean Validation | Dùng cho request DTO; service vẫn kiểm tra quy tắc nghiệp vụ |
| Persistence | Spring Data JPA + Hibernate + PostgreSQL JDBC + HikariCP | Đã có qua dependencies/starter; không thêm pool hoặc ORM khác |
| Security | Spring Security + OAuth2 Resource Server + BCrypt | JWT và xác thực đã dùng; không thêm JJWT chỉ để tạo cùng loại token |
| Observability | Actuator + logging SLF4J/Logback từ starter | Đủ cho health/log cơ bản; không thêm một logging framework thứ hai |
| Test | Spring Boot Test + JUnit/Mockito + Spring Security Test | Đang kiểm tra login, quyền và mapping; không thay bằng thư viện test trùng chức năng |
| Schema | Flyway | Dependency đã có nhưng tắt; không bật migration legacy trên schema mới |

## Ưu tiên bổ sung tiếp theo

| Ưu tiên | Thư viện/công cụ | Khi nên thêm | Lưu ý |
|---|---|---|---|
| Cao | springdoc-openapi | Khi cần Swagger/OpenAPI cho frontend và API contract | Với Boot 3 dùng dòng 2.x; ma trận hiện ghép Boot 3.5 với 2.8.x. Chọn patch tương thích và test; không lấy 3.x dành cho Boot 4 |
| Cao | JaCoCo Maven plugin | Khi thiết lập CI và cần báo cáo coverage | Thêm report trước; ngưỡng coverage phải phản ánh test nghiệp vụ, không đặt tùy ý |
| Cao | Maven Wrapper | Khi muốn developer/CI dùng cùng phiên bản Maven | Commit wrapper và pin distribution; Docker hiện đã cung cấp Maven trong builder |
| Trung bình | Testcontainers PostgreSQL | Khi có Docker trên máy dev/CI và cần test persistence thật | Kiểm tra script, constraint, FK ghép, repository và transaction trên PostgreSQL thật |
| Tùy chọn | Lombok | Khi đội chọn giảm boilerplate entity/constructor | Không cần để MapStruct chạy; tránh @Data/toString/equals tự sinh trên quan hệ JPA và credential |
| Theo nghiệp vụ | Resilience4j | Khi tích hợp API xác thực bên ngoài hoặc dịch vụ không ổn định | Cấu hình timeout/circuit breaker theo yêu cầu thực tế; không retry mù các request có side effect |
| Theo vận hành | Micrometer registry Prometheus | Khi hệ thống có Prometheus/monitoring | Actuator đã có; thêm registry khi có nơi thu metrics và chính sách expose endpoint |

[Ma trận springdoc](https://springdoc.org/v2/#springdoc-openapi-what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot), [JaCoCo Maven](https://www.jacoco.org/jacoco/trunk/doc/maven.html), [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/), [Lombok](https://projectlombok.org/features/), [Resilience4j](https://resilience4j.readme.io/docs/getting-started-3).

Các mục trên là đánh giá, chưa được thêm dependency vào base. Redis/cache, Kafka, Elasticsearch, Querydsl hoặc Apache Commons chỉ thêm khi có use case cụ thể; hiện chưa cần cho module identity.

Nếu chọn Lombok cùng MapStruct sau này, cấu hình cả hai annotation processor và binding tương thích theo [hướng dẫn tích hợp](https://mapstruct.org/documentation/stable/reference/html/#_lombok). Không đưa mapper/entity vào common chỉ để tái sử dụng annotation.

## Quy ước cho mapper mới

```java
@Mapper(config = CentralMapperConfig.class)
public interface ExampleMapper {
    ExampleResponse toResponse(ExampleEntity entity);
}
```

Khi tên field khác nhau hoặc enum cần giá trị nghiệp vụ, khai báo @Mapping/@Named rõ ràng. Với field cố ý không map, khai báo ignore ở mapper cụ thể; không đổi global policy sang IGNORE để bỏ qua lỗi. Mapping không thực hiện query, kiểm tra quyền hoặc biến đổi trạng thái nghiệp vụ.

IntelliJ: Reload Maven, bật annotation processing và giữ JDK 21 cho Project/Maven Runner. Maven là nguồn cấu hình chính; không commit thư mục `.idea`.

## Chuẩn bị đưa base lên Git

- Source hiện dùng `vn.com.coreacademy`, đã bỏ import package cũ trong test và cập nhật tài liệu.
- `.gitignore` loại `.env` thật, target, output/tmp/logs, archive triển khai, IDE và file key/certificate riêng. Chỉ `.env.example` và `.env.ubuntu.example` là mẫu được phép đưa lên Git.
- File mẫu không có password/JWT thật; DB host dùng placeholder. `.gitattributes` chuẩn hóa LF cho source/config để làm việc Windows/Ubuntu.
- `target/generated-sources` là output build, không commit. Configuration metadata sẽ được sinh lại khi build.
- Migration Flyway trong resources là legacy của schema cũ; Flyway vẫn tắt. Script `docs/sql/learning_hub_identity.sql` là nguồn khởi tạo schema hiện tại. Thiết kế migration cho schema mới là bước riêng.
- `.gitignore` không loại file đã tracked ở một repo trước đó. Trước commit cần xem staging diff, không chỉ dựa vào ignore.

Thư mục hiện chưa được khởi tạo Git. Khi chuẩn bị commit, có thể thực hiện:

```bash
git init
git add .
git status --short
git diff --cached --check
git diff --cached
git commit -m "Initialize Java 21 backend base with MapStruct"
```

Chỉ commit sau khi kiểm tra staging; cấu hình remote và push theo repository của đội. Không đưa `.env`, password, JWT, dữ liệu user thật hoặc binary build vào commit.
