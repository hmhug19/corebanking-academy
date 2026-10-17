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

Máy phát triển hiện không có Docker CLI/Engine nên chưa build hoặc chạy image thực tế; bước 5 là kiểm tra container đầy đủ trên Ubuntu. Docker build chạy Maven verify trước khi đóng gói JAR; repository hiện chưa có test source, nên không coi bước này là kiểm thử nghiệp vụ.
