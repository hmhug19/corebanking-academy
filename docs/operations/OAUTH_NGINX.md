# Nginx cho kết nối trợ lý ngoài bằng OAuth

Claude App và Claude Code tìm máy chủ cấp quyền qua hai URL discovery trên **origin của web**
(`app.assistant.external.app-base-url`, production `https://chanacademy.io.vn`), không phải trên `/api`:

- `/.well-known/oauth-protected-resource/api/v1/mcp` (RFC 9728)
- `/.well-known/oauth-authorization-server` (RFC 8414)

Nginx của container web hiện chỉ chuyển `/api/` sang BE, nên hai URL này rơi vào SPA và trả HTML. Phải thêm một
location chuyển mọi đường dẫn bắt đầu bằng `/.well-known/oauth-` sang BE. Các endpoint còn lại (`/api/v1/oauth/...`,
`/api/v1/mcp`) đã nằm dưới `/api/`. Trang Cho phép kết nối (`/tro-ly-ngoai/cho-phep`) là route SPA, không cần đổi.

## Snippet

Đặt trong server block HTTPS đang phục vụ `chanacademy.io.vn` (cùng chỗ với `location /api/`), **trước**
`location /` của SPA. `^~` để regex location (ví dụ cache tĩnh) không giành mất request:

```nginx
location ^~ /.well-known/oauth- {
    proxy_pass http://backend:8019;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

Dùng đúng upstream mà `location /api/` của server đó đang dùng (`backend:8019` trong Compose; với Nginx trên host theo
`deploy/nginx/core-academy.conf.example` là `http://127.0.0.1:8019`). Không thêm `/` cuối vào `proxy_pass`, để giữ
nguyên đường dẫn. Không cấu hình CORS ở Nginx: BE tự trả CORS `*` (không credentials) cho các endpoint OAuth.

### Địa chỉ thật của người gọi

Giới hạn đăng ký client và bắt đầu cấp quyền đếm theo `X-Real-IP` khi request đến từ proxy tin cậy
(`ASSISTANT_EXTERNAL_OAUTH_TRUSTED_PROXIES`, mặc định loopback và dải private). Mọi location chuyển sang BE (cả
`location /api/`) phải **ghi đè** header này bằng địa chỉ Nginx thấy, như snippet trên:

```nginx
proxy_set_header X-Real-IP $remote_addr;
```

Thiếu dòng này thì mọi người gọi chung một bucket (địa chỉ của Nginx) và một script nhỏ chặn được việc kết nối của
mọi người. Nếu BE nhận request trực tiếp từ Internet mà không qua Nginx, đặt
`ASSISTANT_EXTERNAL_OAUTH_TRUSTED_PROXIES` chỉ gồm địa chỉ của proxy thật.

### Chống nhúng trang Cho phép kết nối

Trang `/tro-ly-ngoai/cho-phep` có nút Cho phép; nếu trang khác nhúng được nó trong iframe thì có thể lừa người dùng bấm
(clickjacking). Header chống frame của BE chỉ có trên response API, không có trên HTML của SPA. Thêm trong cùng server
block:

```nginx
location ^~ /tro-ly-ngoai/ {
    add_header Content-Security-Policy "frame-ancestors 'none'" always;
    add_header X-Frame-Options DENY always;
    try_files $uri /index.html;
}
```

Dùng đúng `root`/`try_files` mà `location /` của SPA đang dùng; `add_header` trong một location thay thế header kế
thừa, nên chép thêm các `add_header` mà `location /` có nếu có.

## Áp dụng

1. Sửa file cấu hình Nginx của container web (file được mount vào `/etc/nginx/conf.d/` của container web trên server).
2. Kiểm tra rồi reload, chỉ reload khi `nginx -t` hợp lệ:

   ```bash
   sudo docker exec <container-web> nginx -t
   sudo docker exec <container-web> nginx -s reload
   ```

3. Bản BE phải đã có OAuth (migration `2026-11-30-external-assistant-oauth.sql` đã chạy, `ASSISTANT_EXTERNAL_ENABLED`
   khác `false`); BE cũ trả 404 cho hai URL.

## Kiểm tra

```bash
curl -s https://chanacademy.io.vn/.well-known/oauth-authorization-server
curl -s https://chanacademy.io.vn/.well-known/oauth-protected-resource/api/v1/mcp
curl -s -o /dev/null -D - -X POST https://chanacademy.io.vn/api/v1/mcp | grep -i www-authenticate
```

- Lệnh 1 trả JSON có `"issuer":"https://chanacademy.io.vn"` và `authorization_endpoint`, `token_endpoint`,
  `registration_endpoint` đều trên origin này.
- Lệnh 2 trả JSON có `"resource":"https://chanacademy.io.vn/api/v1/mcp"`.
- `curl -sI https://chanacademy.io.vn/tro-ly-ngoai/cho-phep | grep -i frame` có `frame-ancestors 'none'` và `X-Frame-Options: DENY`.
- Lệnh 3 (không token) có `WWW-Authenticate: Bearer resource_metadata="https://chanacademy.io.vn/.well-known/oauth-protected-resource/api/v1/mcp"`.

Nếu lệnh 1 hoặc 2 trả HTML (`<!doctype html>`), location chưa có hiệu lực hoặc bị location khác giành trước. Nếu
`issuer` sai origin, sửa `ASSISTANT_EXTERNAL_APP_BASE_URL` trong environment rồi recreate BE.

## Kiểm tra đầu cuối trên production

Do người dùng tự làm; agent không nhập mật khẩu trên production. Ghi kết quả vào đây.

- [ ] Claude App: Settings → Connectors → Add custom connector, URL `https://chanacademy.io.vn/api/v1/mcp` → Connect →
      đăng nhập → Cho phép → gọi một tool.
- [ ] Claude Code: `claude mcp add --transport http chan-academy https://chanacademy.io.vn/api/v1/mcp`, rồi `/mcp` →
      Authenticate → đăng nhập → Cho phép → gọi một tool.
- [ ] Ngăn kéo Kết nối trợ lý ngoài liệt kê hai kết nối mới, loại "Cấp"; thu hồi một cái thì Claude báo cần đăng nhập lại.
