# Logging với Log4j2

Source dùng SLF4J API, Log4j2 làm provider thay Logback. Version do Spring Boot BOM quản lý. Cấu hình `src/main/resources/log4j2-spring.xml`.

## Chạy local

Mặc định ghi console và `logs/corebanking-academy.log`, UTF-8, gồm thời gian, level, tên ứng dụng, thread, logger, message và stack trace. Mỗi bản ghi thông thường nằm trên một dòng; stack trace của exception giữ nhiều dòng. Không chèn xuống dòng hoặc thụt lề trong giá trị PATTERN của XML vì Log4j2 có thể giữ nguyên các ký tự đó khi xuất log. Thư mục tự tạo khi chạy. Có thể thêm tùy chọn vào `.env` hiện tại, không cần file env khác:

```properties
LOG_PATH=./logs
LOG_LEVEL_ROOT=INFO
LOG_LEVEL_APP=INFO
```

Dùng DEBUG cho LOG_LEVEL_APP khi cần điều tra. Không bật TRACE hoặc log request body/Authorization header cho API xác thực; không log mật khẩu, password hash, JWT, secret. Cấu hình không bật SQL bind parameter logging hoặc HTTP body logging.

File xoay theo ngày hoặc khi đạt 10 MB; nén gzip vào `logs/archive/`. Tối đa 30 file index mỗi ngày. Cleanup archive chạy khi rollover: xóa file quá 14 ngày hoặc phần archive vượt 200 MB, ưu tiên giữ file mới. Giới hạn này áp dụng archive, không phải quota ổ cứng tức thời.

## Docker / Ubuntu

Hai compose của backend truyền LOG_PATH=/app/logs và mount named volume backend_logs. Dockerfile tạo thư mục cho user UID/GID 10001. Filesystem ứng dụng vẫn read-only, volume log được ghi và giữ khi recreate container. Không chạy down -v nếu cần giữ log. `docker compose logs` vẫn nhận console, Docker json-file có rotation riêng như cấu hình hiện có.

Khi triển khai CI, cập nhật bản compose trên server từ deploy/ci/compose.ci.yaml trước khi sử dụng image mới để có volume ghi log. Source không tự cập nhật file deployment ngoài repository.

## Dùng trong Java

```java
private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MyService.class);
// Parameterized messages, only non-sensitive operational information.
log.info("Completed action for entity {}", entityId);
```

Tham khảo [Spring Boot logging](https://docs.spring.io/spring-boot/3.5/how-to/logging.html) và [Log4j2 RollingFile](https://logging.apache.org/log4j/2.x/manual/appenders/rolling-file.html).

## Request context dùng chung

common.web.RequestIdFilter chạy trước Spring Security để cả response 401/403 có X-Request-Id và correlation ID trong envelope. Tên custom header thuộc common.constants.HttpHeaderNames; RequestIdContext chỉ quản lý MDC key và response ID fallback. Filter validate ID đầu vào, giữ qua dispatch và khôi phục MDC trong finally, không log/cache body. Use case lấy caller qua security.SecurityContextUtils sau xác thực; không đưa JWT/password vào MDC. Chưa tự thêm access log hoặc truyền context qua @Async/executor; hai việc này cần cấu hình riêng khi có luồng sử dụng.
