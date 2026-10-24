# Build và chạy CORE Academy trên Ubuntu

Deployment này chạy backend Java 21 LTS / Spring Boot 3.5.16 GA bằng Docker, kết nối PostgreSQL hiện có. Không cần cài Java/Maven trên host. File `compose.yaml` cũ chỉ phục vụ PostgreSQL local; triển khai backend dùng **`compose.ubuntu.yaml`**.

## 1. Cài Docker Engine và Compose plugin

Nếu server đã có `docker compose version` hoạt động thì bỏ qua bước cài. Các lệnh dưới dành cho Ubuntu được Docker hỗ trợ; tham khảo [hướng dẫn chính thức](https://docs.docker.com/engine/install/ubuntu/) nếu máy đã có Docker/container runtime khác.

```bash
sudo apt update
sudo apt install -y ca-certificates curl git openssl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}")
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
```

## 2. Đưa source lên server

Đặt source ở `/opt/core-academy-backend`. Ví dụ với repository Git, thay `URL_REPO_CORE_ACADEMY` bằng URL thực tế:

```bash
sudo install -d -m 0755 -o "$USER" -g "$(id -gn)" /opt/core-academy-backend
git clone URL_REPO_CORE_ACADEMY /opt/core-academy-backend
cd /opt/core-academy-backend
```

Nếu dùng SFTP/SCP, copy `Dockerfile`, `.dockerignore`, `compose.ubuntu.yaml`, `pom.xml`, `src/`, `docs/sql/learning_hub_identity.sql` và các hướng dẫn cần dùng. Không cần copy `target/` hoặc `.env` local. Dockerfile giữ bản SQL chuẩn trong build context; file không tự chạy hoặc nằm trong image runtime.

Có thể đóng gói bằng PowerShell tại máy Windows, chỉ chọn các file source/config mẫu:

```powershell
Set-Location D:\POC\corebanking-academy
tar -czf core-academy-source.tar.gz Dockerfile .dockerignore compose.ubuntu.yaml .java-version pom.xml src docs/sql docs/operations deploy
scp .\core-academy-source.tar.gz ubuntu@IP_SERVER:/tmp/
```

Trên Ubuntu, giải nén vào thư mục deployment:

```bash
sudo install -d -m 0755 -o "$USER" -g "$(id -gn)" /opt/core-academy-backend
tar -xzf /tmp/core-academy-source.tar.gz -C /opt/core-academy-backend
cd /opt/core-academy-backend
```

Thay `ubuntu@IP_SERVER` bằng user SSH và IP thực tế; thêm `-i PATH_TO_KEY` cho scp nếu cần SSH key. Các lệnh này dùng source local, không cần repository Git. Gói chỉ gồm các đường dẫn được chọn, không chứa `.env` local.

## 3. Cấu hình trên Ubuntu

```bash
cd /opt/core-academy-backend
touch .env.ubuntu
chmod 600 .env.ubuntu
nano .env.ubuntu
```

Điền các giá trị:

| Biến | Ý nghĩa |
|---|---|
| IMAGE_TAG | Tag phiên bản, ví dụ `20261013-001`; dùng tag mới cho mỗi bản deploy |
| DB_URL | JDBC URL PostgreSQL, ví dụ `jdbc:postgresql://DB_HOST:5432/appdb?sslmode=require&currentSchema=learning_hub` |
| DB_USER / DB_PASSWORD | Tài khoản kết nối database và mật khẩu thực tế |
| JWT_SECRET | Secret riêng của môi trường Ubuntu, tối thiểu 32 byte UTF-8 |
| CORS_ALLOWED_ORIGINS | Origin frontend, ví dụ `https://academy.example.com`; nhiều origin ngăn cách bằng dấu phẩy |
| BIND_IP / HOST_PORT | Mặc định `127.0.0.1:8019`, truy cập qua reverse proxy trên host |
| APP_MEMORY_LIMIT | Giới hạn RAM container, mặc định `768m` |
| APP_BOOTSTRAP_ADMIN_ID/USER/PASSWORD | Để trống cả ba hoặc điền đủ cả ba để khởi tạo admin |

Sinh JWT secret mới bằng lệnh sau, rồi paste giá trị vào `.env.ubuntu`:

```bash
openssl rand -base64 64 | tr -d '\n'
```

Không để các giá trị `CHANGE_ME`, `DB_HOST`, `FRONTEND_DOMAIN` trong file chạy thật. Không truyền secret qua build argument; Compose chỉ truyền secret vào môi trường runtime. `.env*` bị loại khỏi build context và không được commit file môi trường thật; kiểm tra staging thực tế vì ignore không bảo vệ file đã tracked hoặc bị negate. [Docker: quản lý secret khi build](https://docs.docker.com/build/building/secrets/).

Ứng dụng trong container không mount/read `.env.ubuntu`: Compose đọc file này và truyền những biến được khai báo trong `environment`. Không dùng `localhost` làm DB host nếu PostgreSQL nằm trên máy khác; trong container, localhost là chính container.

## 4. Chuẩn bị PostgreSQL trước khi chạy app

- Database trong ví dụ là `appdb`, schema là `learning_hub`; đây là hai tên khác nhau.
- Chạy `docs/sql/learning_hub_identity.sql` một lần trong database đích bằng tài khoản có quyền tạo schema/bảng, nếu schema chưa tồn tại. Script chạy trong transaction, không phải migration cho database đã có dữ liệu.
- Nếu database đã có đủ bảng, không chạy lại script tạo mới. Source sử dụng `ddl-auto=validate`; Flyway đang tắt.
- Tài khoản runtime cần quyền kết nối DB, sử dụng schema, thao tác bảng và sequence theo nghiệp vụ. Tài khoản tạo schema có thể khác tài khoản runtime.
- PostgreSQL phải cho phép **IP kết nối thực tế của server Ubuntu**, thay cho IP laptop. Với PostgreSQL từ xa, thường là IP public/NAT của Ubuntu. Rule `pg_hba.conf` cần khớp database, user và IP; đặt trước rule reject. [Quy tắc HBA](https://www.postgresql.org/docs/current/auth-pg-hba-conf.html).

Nếu cần tạo quyền runtime ban đầu, DBA có thể chạy các lệnh sau trong database đích, thay `dev_user` bằng user thực tế:

```sql
GRANT CONNECT ON DATABASE appdb TO dev_user;
GRANT USAGE ON SCHEMA learning_hub TO dev_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA learning_hub TO dev_user;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA learning_hub TO dev_user;
```

Đây là quyền cho các đối tượng đã tồn tại. Quyền của bảng/sequence được tạo sau cần được DBA cập nhật theo role sở hữu đối tượng.

File SQL đã có super.admin (000001) và dữ liệu demo; để trống APP_BOOTSTRAP_ADMIN_ID/USER/PASSWORD khi dùng bộ seed. Mật khẩu tạm Demo@123 cần đổi ngay sau đăng nhập đầu, tối thiểu 6 ký tự và tối đa 72 byte UTF-8. Xem [SQL README](../sql/README.md). Bootstrap Java chỉ là lựa chọn khởi tạo có chủ đích, không tự nâng quyền/reset user đã tồn tại.

## 5. Build và chạy

Chạy từ thư mục project trên Ubuntu:

```bash
sudo docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml config --quiet
sudo docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml build --pull backend
sudo docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml up -d --no-build --wait --wait-timeout 180
sudo docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml ps
curl -fsS http://127.0.0.1:8019/actuator/health
```

Dockerfile build bằng Maven/JDK 21 và chạy Maven verify trước khi đóng gói; verify chỉ chạy test thực sự tồn tại, repository hiện chưa có test source. Maven dependencies được cache bằng BuildKit. Runtime dùng Temurin JRE 21, user UID 10001, filesystem read-only và `/tmp` tạm để Tomcat hoạt động. Healthcheck kiểm tra `/actuator/health`, bao gồm tình trạng datasource mặc định của Spring Boot.

Kỳ vọng health trả `{"status":"UP"}`. Nếu đổi HOST_PORT, thay cổng trong lệnh curl và upstream reverse proxy; cổng bên trong container luôn là 8019. Container tự khởi động lại khi process thoát hoặc Docker khởi động; trạng thái unhealthy tự nó không làm Docker restart container.

Xem log:

```bash
sudo docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml logs --tail=100 -f backend
```

Tính năng AI (Knowledge/RAG) cần Ollama; khi Ollama chạy trên Mac riêng, xem [TAILSCALE_OLLAMA.md](TAILSCALE_OLLAMA.md).

## 6. Truy cập từ máy khác

Mặc định backend chỉ publish vào loopback của host. Có hai cách truy cập:

**Reverse proxy Nginx:** dùng mẫu `deploy/nginx/core-academy.conf.example`, thay domain và port upstream nếu cần. Cài và cấu hình trên host:

```bash
sudo apt install -y nginx
sudo cp deploy/nginx/core-academy.conf.example /etc/nginx/sites-available/core-academy
sudo nano /etc/nginx/sites-available/core-academy
sudo ln -s /etc/nginx/sites-available/core-academy /etc/nginx/sites-enabled/core-academy
sudo nginx -t
sudo systemctl reload nginx
```

Mẫu là HTTP reverse proxy. Với truy cập production, cấu hình chứng chỉ TLS/HTTPS trên Nginx hoặc ingress hiện có; CORS phải khớp origin của frontend. Cho phép inbound 80/443 trên firewall/security group theo cách expose đã chọn.

**Truy cập trực tiếp cổng backend cho demo:** đổi `BIND_IP=0.0.0.0` trong `.env.ubuntu`, cho phép cổng HOST_PORT trên lớp mạng của server, rồi recreate:

```bash
sudo docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml up -d --no-build --wait --wait-timeout 180
```

Khi đó gọi `http://IP_SERVER:8019/actuator/health`. Port Docker publish có thể vượt qua rule UFW thông thường; cấu hình giới hạn truy cập theo lớp mạng/Docker firewall phù hợp. [Docker và firewall Ubuntu](https://docs.docker.com/engine/install/ubuntu/#firewall-limitations).

## 7. Cập nhật và rollback

Đưa source phiên bản mới lên server, sửa IMAGE_TAG sang tag mới, sau đó chạy lại hai lệnh build và up ở bước 5. Khi chỉ đổi biến runtime, chỉ cần `up`, không cần build. Với một container backend, recreate có thể gây gián đoạn ngắn.

Giữ image phiên bản trước để rollback. Ví dụ quay về tag đã build `20261013-001`:

```bash
sudo env IMAGE_TAG=20261013-001 docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml up -d --no-build --wait --wait-timeout 180
```

Rollback image không rollback database. Thay đổi schema trong các bản sau cần kế hoạch riêng. Không chạy `docker image prune -a` nếu còn cần image cũ.

Dừng backend:

```bash
sudo docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml down
```

PostgreSQL đang ở ngoài Compose này nên lệnh trên chỉ dừng/xóa container và network của backend.

## 8. Lỗi thường gặp

| Lỗi | Kiểm tra |
|---|---|
| DB_URL/JWT_SECRET is required | File `.env.ubuntu` và tham số `--env-file`; không chạy nhầm compose.yaml |
| pg_hba.conf rejects connection | Rule cho IP server Ubuntu, đúng database/user, SSL và thứ tự trước reject |
| Password authentication failed | Mật khẩu và tài khoản PostgreSQL, không phải password của user Learning Hub |
| Missing table/column | Script/schema thực tế có đồng bộ với entity không; database name và schema name |
| Permission denied for schema/table/sequence | GRANT cho DB_USER trên đúng database và đối tượng |
| Bootstrap requires ADMIN_ID... | Điền đủ ba biến hoặc để trống cả ba |
| unhealthy / health DOWN | Xem log và kết nối database; thời gian khởi động/giới hạn RAM |
| Frontend bị CORS | Origin frontend thực tế phải khớp CORS_ALLOWED_ORIGINS |
| Container không vào được DB localhost | Đổi DB_URL sang host/IP PostgreSQL mà container truy cập được |

## Trạng thái kiểm tra

Máy phát triển hiện không có Docker CLI/Engine nên chưa build hoặc chạy image thực tế; bước 5 là kiểm tra container đầy đủ trên Ubuntu. Docker build chạy Maven verify trước khi đóng gói JAR; không coi đóng gói JAR là kiểm thử nghiệp vụ.

<a id="aistor-integration"></a>

## AIStor / S3 — bàn giao và tích hợp backend

Cập nhật 24/10/2026. Thông tin triển khai dưới đây được tổng hợp từ note `AISTOR-UBUNTU-SETUP.md` do người dùng cung cấp. Đây là kết quả kiểm tra được bàn giao, không phải xác minh lại server trong lần cập nhật tài liệu này. Không chép license, mật khẩu hoặc access key vào repository.

### Hạ tầng đã bàn giao

| Thành phần | Giá trị |
|---|---|
| Ubuntu / SSH | 103.121.90.119, port 26266, user dev_user |
| AIStor | RELEASE.2026-09-19T17-05-25Z |
| Image đã pin | quay.io/minio/aistor/minio@sha256:107cf2014a9583c74c11e3cdbd6903d89c2244355886b89ce532f4ecae9c23f3 |
| Compose / container | /opt/core-academy/aistor/compose.yaml / core-academy-aistor |
| Bucket | academy-media, private, anonymous access bị từ chối |
| Endpoint từ Docker network project_edge | http://aistor:9000 |
| Endpoint từ Ubuntu host | API http://127.0.0.1:9000; Console http://127.0.0.1:9001 |
| Tài khoản | academy_admin quản trị; academy_backend dùng cho BE |
| Policy BE | academy-media-backend, tại /opt/core-academy/aistor/backend-policy.json |
| Dữ liệu bền vững | /opt/core-academy/aistor/data → /mnt/data |
| Giới hạn ban đầu | RAM 1 GiB, CPU 1.5; server tổng RAM 4 GiB |

Theo note: ready/Console hoạt động; user BE upload/download đúng checksum và xóa object thử thành công; anonymous bị 403, quyền admin bị từ chối; recreate container giữ dữ liệu/credentials. DB/Redis/BE healthy tại thời điểm bàn giao. Các kiểm tra này chưa chứng minh API upload/viewer của ứng dụng đã dùng AIStor.

License và credentials nằm trong `/opt/core-academy/aistor/secrets/`: `minio.license`, `root_user`, `root_password`, `backend_access_key`, `backend_secret_key`, `backend.env`. Directory root:root mode 700, file bí mật mode 600. Giữ secrets ngoài Git/CI log/frontend; BE dùng tài khoản giới hạn bucket, không dùng tài khoản quản trị và không tự tạo bucket/IAM khi khởi động.

### Kết nối và cấu hình

Mở Console hoặc kết nối S3 từ PC qua tunnel; giữ phiên SSH mở:

```powershell
ssh -N -L 9001:127.0.0.1:9001 -L 9000:127.0.0.1:9000 -p 26266 dev_user@103.121.90.119
```

Console tại `http://localhost:9001`; BE chạy trên PC dùng endpoint `http://localhost:9000`. Nếu cổng PC bận, đổi local port của tunnel và endpoint tương ứng. Không dùng `127.0.0.1:9000` từ container BE để gọi container AIStor. Cổng 9000/9001 trên server chỉ bind loopback; không mở thêm public port để thử tích hợp.

File root-only `backend.env` đã bàn giao các tên biến dưới đây. **Source hiện tại bind các biến này vào app.storage.s3**; endpoint và credentials là bắt buộc khi chạy BE:

| Biến | Giá trị / cách cấp |
|---|---|
| S3_ENDPOINT | Server BE: http://aistor:9000; BE local qua tunnel: http://localhost:9000 |
| S3_BUCKET | academy-media |
| S3_REGION | us-east-1 |
| S3_PATH_STYLE_ACCESS | true; client phải truy cập bucket theo path, không tự suy virtual-host DNS |
| S3_ACCESS_KEY / S3_SECRET_KEY | Cấp riêng từ bộ credentials backend, không ghi giá trị vào tài liệu |

`deploy/ci/compose.ci.yaml` và `compose.ubuntu.yaml` đều nối `project_edge`, truyền S3 config và mount backend_upload_tmp vào /app/uploads-tmp. Cần rebuild image và recreate đúng BE compose đang sử dụng; `--env-file` của Compose chỉ phục vụ nội suy, không tự đưa mọi biến vào container. File root-only trên host không tự được Spring đọc. Chọn cấp environment/secrets có kiểm soát, không mở quyền file secrets cho mọi user; không chạy `docker compose config`/dump environment ra log có thể lộ giá trị.

### Source BE đã tích hợp

| Vị trí | Trách nhiệm hiện tại |
|---|---|
| storage.config.S3Properties / S3Config | Bind/validate cấu hình, che credential, client dùng chung và timeout hữu hạn; không tự tạo bucket/IAM |
| storage.service.ObjectStorage | PUT file staging, HEAD metadata, GET đúng offset/length + If-Match ETag, DELETE object trong bucket cấu hình; không biết user/role/khóa học |
| storage.service.UploadStaging | File tạm và SHA-256 theo stream; StagedFile dùng try-with-resources để dọn file, bên gọi đóng input |
| learning.service.LearningAssetStore | Validation signature/extension/size/metadata, tạo key learning/UUID, PUT qua ObjectStorage; lỗi PUT dọn đúng key và giữ lỗi gốc |
| LearningAssetService | Kiểm tra quyền trước upload và trước commit; lưu storageType=s3, audit; lỗi metadata dọn đúng object, giữ lỗi gốc nếu cleanup thất bại |
| storage.web.ObjectDownloadHandler | Range/If-Range, HEAD, HTTP header, đóng/copy stream 64 KiB; dùng metadata ObjectDownload đã kiểm quyền, không chứa policy |
| LearningAssetController | Nhận request, gọi service lấy metadata đã kiểm quyền rồi giao ObjectDownloadHandler; transaction kết thúc trước stream |
| Compose / Dockerfile | Volume disk writable backend_upload_tmp cho staging và servlet multipart; runtime user 10001:10001; /tmp 128 MiB không dùng chứa upload lớn |
| CorsConfig | Cho phép HEAD/Range/If-Range, expose Content-Range/Accept-Ranges/Content-Length/Content-Disposition/ETag |

Phạm vi chuyển kho lần này là tài nguyên Learning: chỉ S3, không adapter/fallback local. Module Knowledge xuất hiện từ thay đổi song song vẫn có app.knowledge.storage-dir riêng; chưa chuyển file Knowledge trong task này, không coi mọi module đã dùng S3. MinIO SDK 9.0.3; Maven dùng okhttp-jvm 5.3.2 vì artifact okhttp gốc là multiplatform metadata, Kotlin runtime 2.2.21 theo SDK. Spring/Jackson/Hibernate vẫn theo Boot BOM. Các phiên bản và processors đầy đủ ở pom.xml. Xem [SDK chính thức](https://github.com/minio/minio-java) và [Java API](https://docs.min.io/aistor/developers/sdk/java/api/).

### Cấu hình trước khi chạy

Giữ một file .env local tại thư mục project. Thêm các tên biến sau, tự cấp giá trị credentials đã bàn giao qua kênh riêng; không commit/export file chứa secrets:

```dotenv
# BE local qua SSH tunnel; BE trên Ubuntu Docker dùng http://aistor:9000.
S3_ENDPOINT=http://localhost:9000
S3_BUCKET=academy-media
S3_REGION=us-east-1
S3_PATH_STYLE_ACCESS=true
S3_ACCESS_KEY=
S3_SECRET_KEY=
```

Credentials rỗng hoặc thiếu endpoint làm ứng dụng không khởi động; không tự dùng root/default credential. Optional: S3_CONNECT_TIMEOUT_SECONDS=5, S3_READ_TIMEOUT_SECONDS=60, S3_CALL_TIMEOUT_SECONDS=600. Nếu cần UPLOAD_TEMP_DIR local, dùng thư mục tồn tại/writable. Trên Docker hai compose tự đặt /app/uploads-tmp. Không dùng LEARNING_ASSET_DIR nữa. Không đọc/ghi file .env cá nhân trong thay đổi source này.

Trên Ubuntu, người vận hành cấp biến vào cấu hình BE hiện có từ bộ secrets root-only, đặt S3_ENDPOINT=http://aistor:9000, rebuild/pull image mới rồi recreate BE. Không chỉ restart container cũ vì biến môi trường không tự đổi. Xác nhận network project_edge đã tồn tại từ hạ tầng AIStor. Volume staging không phải kho media hoặc backup; file tạm được dọn bình thường, sau crash cần kiểm tra file tồn đọng trước khi dọn có kiểm soát. Không đặt tất cả upload vào tmpfs trên server 4 GiB.

Giữ hai API có sẵn:

- `POST /api/v1/learning-assets`: multipart ownerOrgId, file, durationSeconds/pageCount theo loại tệp; JSON BaseResponse, không trả storage key.
- `GET /api/v1/learning-assets/{assetId}/content`: JWT và quyền hiện có; học viên gửi blockId/enrollmentId. Thành công trả binary, không BaseResponse.

**Luồng đã triển khai trong source:** browser → BE có xác thực → AIStor nội bộ. Không cần presigned URL hoặc CORS bucket trong luồng này. Với cross-origin browser → BE, CORS của BE vẫn cần kiểm tra. Thẻ video/iframe thông thường không tự gắn Bearer header; FE cần player có cơ chế request kèm Authorization, hoặc thiết kế cơ chế xác thực media riêng trước khi dùng src trực tiếp. Không thêm accessToken vào URL, không tải cả video thành blob chỉ để tránh bài toán Range.

Chỉ làm presigned URL khi có endpoint HTTPS mà browser thật truy cập được. Không trả `http://aistor:9000`, loopback trên server hoặc localhost của riêng PC cho người dùng khác; không thay hostname sau khi ký. Khi chuyển sang browser truy cập S3 trực tiếp phải thiết kế lại TTL, CORS và hành vi thu hồi quyền vì URL đã ký còn dùng được tới khi hết hạn.

### Điều kiện kỹ thuật trước khi bật S3

1. Upload: giữ kiểm tra extension + signature, kích thước và metadata video/PDF hiện có; tính SHA-256 theo stream, không dùng getBytes/readAllBytes cho video. Multipart/staging disk phải tính cả file tạm của servlet và bản staging kiểm tra nội dung; không tăng tmpfs lên hàng GiB trên máy 4 GiB để xử lý tệp lớn.
2. Key: UUID hoặc prefix do BE tạo, không dùng đường dẫn/tên file từ client làm key. Bucket lấy từ config. Chỉ lưu metadata khi PUT thành công; nếu DB lỗi phải dọn đúng key. DB và S3 không có transaction chung: lỗi cleanup/crash vẫn có thể tạo orphan; ghi nhận an toàn để đối soát, không báo đã atomic tuyệt đối và không xóa quét bucket.
3. Dữ liệu local cũ: backend trả LEARNING-ASSET_STORAGE_UNSUPPORTED sau kiểm tra quyền. Chưa tự migrate, không có fallback local. Nếu có dữ liệu cần giữ, copy đúng file sang S3, kiểm SHA-256/size rồi cập nhật storage_type/key bằng kế hoạch riêng. Không chỉ đổi local thành s3 trong DB và không xóa file gốc trước khi đối soát.
4. Video/PDF: không Range trả 200; single byte range hợp lệ trả 206 với Content-Range/Content-Length/Accept-Ranges đúng byte. Range vượt độ dài trả 416 với Content-Range dạng bytes */total. Multiple ranges hoặc cú pháp sai trả 416; If-Range chỉ chấp nhận strong ETag khớp, ngày/ETag khác trả toàn file 200. Mỗi GET mở stream mới, đóng khi hoàn tất/client ngắt; không giữ transaction/DB lock trong thời gian phát video.
5. Lỗi: không đổi AccessDenied/timeout/credential sai từ AIStor thành dữ liệu trống hoặc lỗi validation. Mapping theo lỗi hệ thống hiện có, không lộ SDK exception/key/secret. Nếu stream đã bắt đầu, không ghép JSON BaseResponse vào phần binary còn lại. Không mặc định healthUP hiện tại chứng minh bucket/S3 có thể đọc ghi; kiểm tra dependency storage riêng khi triển khai.

### Kiểm chứng source và nghiệm thu server

Kiểm tra cục bộ: compile/package thành công; full Maven clean verify trên workspace mới nhất có 1 lỗi PdfTextExtractorTest do CR/LF trên Windows, 33 test Knowledge cần Docker bị skip (phần thay đổi song song ngoài storage); YAML parse; MinIO SDK thật với S3 protocol fake localhost, Spring HTTP và H2 tạm (upload/checksum, video multipart 135 MB, byte ranges/HEAD/416, quyền, lỗi upstream và cleanup khi DB rollback); cấu hình thiếu/sai; hồi quy Identity. Đây không phải benchmark server hoặc test AIStor thật. Harness ở ngoài repository, không thêm file test source. H2 chỉ điều chỉnh timestamptz/bỏ index biểu thức không hỗ trợ và thay advisory lock trong harness; SQL nguồn giữ PostgreSQL.

| Nhóm | Kiểm tra | Điều kiện đạt |
|---|---|---|
| Build/config | Maven clean verify, cấu hình thiếu/sai endpoint/credential | Build được; lỗi config rõ mà không lộ secret; không fallback local, không dùng credential mặc định |
| Network/IAM | Từ đúng container BE gọi AIStor; dùng tài khoản backend với key test riêng | Resolve aistor, PUT/HEAD/GET/DELETE object thử; checksum đúng; anonymous và quyền quản trị bị từ chối |
| Upload | PNG/PDF/video hợp lệ; sai signature/extension, thiếu metadata, vượt byte limit | Chỉ tệp hợp lệ có metadata ready và object; file lỗi được dọn |
| Download | Toàn file; Range đầu/giữa/cuối/suffix; range ngoài độ dài; hủy tải | Đúng byte/checksum/header 200/206/416; không rò stream hoặc tải cả file cho một đoạn nhỏ |
| Quyền | Không token, learner khác enrollment, sai block, revoked assignment, chưa tới startAt, khóa inactive | Bị từ chối trước khi stream; không vượt quyền bằng đổi assetId/blockId/enrollmentId |
| Rollback | Giả lập lỗi metadata/audit sau PUT, lỗi cleanup, S3 timeout | Không lưu row ready sai; dọn đúng object hoặc ghi nhận orphan, không ảnh hưởng object khác |
| Tương thích | Row local cũ; restart/recreate BE và AIStor | File local cũ báo cần chuyển; object đã chuyển/new S3 không mất sau restart |
| Tải thực tế | Video lớn hơn 128 MiB, tua trên browser, vài upload/view đồng thời | Không đầy /tmp, không heap spike theo dung lượng file; đo RAM/CPU/disk/latency trên giới hạn server |

Dùng file tổng hợp và tài khoản được phép; prefix object kiểm tra riêng, chỉ xóa object do lượt test tạo. Test quyền ngoài bucket/admin thực hiện bằng thao tác đọc không thay đổi cấu hình server. Không thêm file test vào source nếu chưa được yêu cầu rõ theo guideline; có thể dùng kiểm tra ngoài repo/Postman hiện có. H2/mock không thay xác minh AIStor thật, PostgreSQL hoặc tua video trên browser.

Thứ tự nghiệm thu: adapter với mock/fault injection → local BE qua SSH tunnel → BE container trên project_edge → FE upload/view/tua video. Chỉ công bố tích hợp hoàn tất sau khi test từ API ứng dụng, không chỉ từ mc. Code/Compose đã cập nhật trong repository; chưa deploy, migrate DB, đọc secret hoặc chạy test với AIStor Ubuntu thật. Kiểm thử giao thức S3 giả lập/H2 chỉ là kiểm tra cục bộ, chưa chứng minh network/IAM và tải trên server.

### Vận hành và backup

```bash
sudo docker compose -f /opt/core-academy/aistor/compose.yaml ps
sudo docker compose -f /opt/core-academy/aistor/compose.yaml logs --tail=100
curl -f http://127.0.0.1:9000/minio/health/ready
sudo docker stats --no-stream core-academy-aistor
```

Chỉ chia sẻ log đã lọc credential/license; mc chính thức tại `/opt/core-academy/aistor/bin/mc`, cấu hình credential qua kênh riêng. Không sửa/xóa trực tiếp file nội bộ trong data directory. Image pin digest; không tự đổi sang latest khi restart.

Theo note, một node/một filesystem, chưa có HA hoặc backup tự động ngoài server. Media cùng ổ DB/Docker, khoảng trống khoảng 35 GiB chỉ là số tại thời điểm bàn giao, cần đo lại trước upload. Backup PostgreSQL không chứa object video/PDF; cần sao lưu object và cấu hình/IAM tới đích độc lập, backup license/credential mã hóa riêng và kiểm tra phục hồi.

Nguồn tham khảo: [AIStor container installation](https://docs.min.io/aistor/installation/container/install/) và [Java SDK API](https://docs.min.io/aistor/developers/sdk/java/api/). Trạng thái cài đặt của server lấy từ note bàn giao, không suy từ tài liệu sản phẩm.
