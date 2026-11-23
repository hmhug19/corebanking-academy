# CI/CD backend lên Ubuntu

Pipeline chạy khi push/merge vào `main`. Pull request vào `main` chỉ build/test. Có thể bấm Run workflow và chọn `main` để deploy thủ công.

Dockerfile hiện tại build/test bằng Java 21; image được push private lên `ghcr.io/hmhug19/corebanking-academy` và deploy bằng digest bất biến. Workflow dùng GITHUB_TOKEN của từng job, không cần lưu GitHub PAT trên Ubuntu.

## Kích hoạt

1. Server cần cài `deploy/ci/compose.ci.yaml` vào `/etc/core-academy-ci/compose.yaml` và `deploy/ci/deploy-ci.sh` vào `/usr/local/sbin/core-academy-ci-deploy`, owner root, quyền 644 và 755 tương ứng.
2. SSH key CI được cấu hình trên `dev_user` với forced command và cấm forwarding/PTY. Sudo chỉ miễn mật khẩu cho script deploy cố định, không cho shell hoặc lệnh sudo bất kỳ.
3. Thêm repository Actions secret `UBUNTU_DEPLOY_SSH_KEY` bằng private key CI đã chuẩn bị riêng. Không commit hoặc gửi private key vào chat.
4. Merge workflow vào `main`, hoặc bấm Run workflow trên `main` sau khi merge.

DB/JWT/runtime config lấy từ `/opt/core-academy/backend/.env.ubuntu` hiện có. PostgreSQL, Redis và dữ liệu không được recreate theo pipeline. Không chạy migration tự động; Flyway hiện tắt trong ứng dụng.

SCORM cần thêm `SCORM_LMS_ORIGIN` và `SCORM_PLAYER_ORIGIN` vào file environment trên host, đồng thời
cập nhật `/etc/core-academy-ci/compose.yaml` từ `deploy/ci/compose.ci.yaml`. Hai origin là bắt buộc với
Compose deploy; thiếu sẽ dừng trước khi thay container. Chỉ cập nhật image không cập nhật file Compose
root-owned trên server. Dùng player `https://chanacademy.io.vn:8020`, LMS `https://chanacademy.io.vn`;
Nginx dùng HTTPS 8020 công khai, upstream player HTTP 8020 nội bộ. Cấu hình Nginx và port mapping xem
[Triển khai SCORM](DEPLOY_UBUNTU.md#scorm-12). Sau thay environment phải recreate BE; `restart` không nạp biến mới.

Trợ lý ngoài kết nối bằng OAuth cần Nginx của web chuyển `/.well-known/oauth-` sang BE; snippet, reload và
lệnh kiểm tra ở [OAUTH_NGINX.md](OAUTH_NGINX.md). Image mới không tự thêm location này.

Kiểm tra riêng ba biến không chứa secret, không dump toàn bộ environment:

```bash
sudo docker exec core-academy-backend-1 sh -c 'printf "SCORM_PLAYER_PORT=%s\nSCORM_PLAYER_ORIGIN=%s\nSCORM_LMS_ORIGIN=%s\n" "$SCORM_PLAYER_PORT" "$SCORM_PLAYER_ORIGIN" "$SCORM_LMS_ORIGIN"'
```

## Deploy và rollback

CI chạy Maven verify trong Docker build trước khi publish; repository hiện chưa có test source nên bước này mới xác minh build. Server pull image trước khi cập nhật container, đợi health tối đa 180 giây và reload Nginx. Nếu startup/health thất bại, server khôi phục image trước (có sẵn local). Lần đầu không có image trước thì không thể rollback.

Các deployment được khóa bằng concurrency của Actions và flock trên server. Digest đang chạy được lưu trong `/opt/core-academy/backend/ci-active-image`; digest/image trước đó nằm ở `ci-previous-image`. Không prune image trước khi bản mới đã ổn định.

Sau khi chuyển sang CI, dùng `/opt/core-academy/backend/run-ci.sh` để xem trạng thái/log/restart bản image đang chạy. Script deploy thủ công cũ có thể đưa ứng dụng trở lại image local trước đây nếu chạy `up`, nên không dùng nó để cập nhật bản CI.

## Secrets và kết nối

Chỉ private key SSH cần thêm thủ công. Host, port, username và public host key được cố định trong workflow theo server này. Host key được kiểm tra nghiêm ngặt; khi thay server/host key, cập nhật workflow sau khi xác minh.

GITHUB_TOKEN chỉ được gửi qua SSH mã hóa và lưu trong thư mục cấu hình Docker tạm trong `/run`, được xóa khi script kết thúc. Key CI chỉ được gọi script deployment. Registry package mới được liên kết với repository qua workflow và OCI source label.

API giữ tại `http://103.121.90.119/api/v1/...`, health tại `http://103.121.90.119:8019/actuator/health`. HTTP hiện dành cho demo; HTTPS web/API cần cấu hình riêng.

## Phạm vi

Repository hiện chứa BE, chưa có source Angular. Pipeline này chỉ triển khai BE; giao diện Angular demo hiện có giữ nguyên.

