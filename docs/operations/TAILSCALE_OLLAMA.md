# Kết nối backend tới Ollama trên Mac qua Tailscale

Server Ubuntu chạy backend và xử lý dữ liệu thông thường; phần AI (chat RAG và embedding) do Ollama trên một MacBook xử lý. Backend chỉ gọi Ollama qua `knowledge.ollama.base-url` (biến `OLLAMA_BASE_URL`, xem [application.yml](../../src/main/resources/application.yml)), nên chỉ cần một đường mạng riêng từ server tới cổng `11434` của Mac. Tài liệu này dùng Tailscale (WireGuard) để tạo đường đó mà không mở port nào ra Internet.

```
Ubuntu server (backend container) ──Tailscale, mã hóa──▶ MacBook :11434 (Ollama)
```

> **Bảo mật:** Ollama không có xác thực. Không mở cổng `11434` ra Internet, không port-forward trên router, không dùng tunnel công khai. Chỉ truy cập qua tailnet và giới hạn bằng ACL ở bước 5.

## 0. Phân công và điều kiện trước

| Việc | Người làm | Mục |
|---|---|---|
| Cài Ollama, model và Tailscale trên Mac | Người giữ Mac | 1 |
| Cài Tailscale trên server, đăng nhập tailnet | Admin server (có `sudo` đầy đủ) | 2–4 |
| Cấu hình ACL, tag, key expiry | Owner/Admin của tailnet | 5 |
| Kiểm tra kết nối | Admin server | 6 |
| Trỏ backend tới Mac | Admin server | 7 |

Điều kiện:

- Server Ubuntu 22.04/24.04, user có `sudo` đầy đủ. User `dev_user` của CI chỉ được sudo cho script deploy ([CICD.md](CICD.md)), không đủ để cài gói.
- Server ra được Internet tới `pkgs.tailscale.com`, `login.tailscale.com` và UDP `41641` (nếu UDP bị chặn, Tailscale vẫn chạy qua relay DERP nhưng chậm hơn).
- Một tài khoản Tailscale chung cho cả Mac và server (cùng tailnet).

## 1. Chuẩn bị trên Mac (người giữ Mac)

```bash
brew install ollama
ollama pull qwen3:8b
ollama pull bge-m3
```

Model phải trùng `OLLAMA_CHAT_MODEL` / `OLLAMA_EMBED_MODEL` (mặc định `qwen3:8b` và `bge-m3`; cột vector của backend yêu cầu embedding 1024 chiều của `bge-m3`).

Cho Ollama lắng nghe ngoài `localhost` rồi khởi động lại:

```bash
# Ollama chạy bằng app macOS
launchctl setenv OLLAMA_HOST 0.0.0.0:11434
# Thoát hẳn app Ollama rồi mở lại

# Hoặc chạy bằng Homebrew service
brew services restart ollama   # khi đó đặt OLLAMA_HOST trong plist/service tương ứng
```

Cài Tailscale (App Store hoặc `brew install --cask tailscale`), đăng nhập đúng tailnet, rồi kiểm tra. Nếu dùng Homebrew formula `brew install tailscale` (chỉ CLI), phải chạy daemon trước bằng `sudo brew services start tailscale` rồi `sudo tailscale up`; thiếu bước này, lệnh `tailscale` báo `dial unix /var/run/tailscaled.socket: no such file or directory`. Không cài đồng thời formula và app.

```bash
tailscale ip -4                        # ghi lại IP 100.x.y.z của Mac, gửi cho admin server
curl http://localhost:11434/api/version
```

Giữ Mac luôn thức khi cần phục vụ AI: System Settings → Battery/Energy → bật *Prevent automatic sleeping when the display is off* (khi cắm sạc), hoặc chạy `caffeinate -s` trong một terminal. Bật macOS Firewall vẫn được, nhưng phải cho phép `ollama` nhận kết nối đến.

## 2. Cài Tailscale trên server

SSH vào server bằng user admin:

```bash
ssh -p 26266 <admin-user>@103.121.90.119
sudo -v                                 # xác nhận có sudo
lsb_release -cs                         # jammy (22.04) hoặc noble (24.04)
```

Cài từ repo apt chính thức (thay `noble` bằng kết quả `lsb_release -cs`):

```bash
CODENAME=$(lsb_release -cs)
curl -fsSL "https://pkgs.tailscale.com/stable/ubuntu/${CODENAME}.noarmor.gpg" \
  | sudo tee /usr/share/keyrings/tailscale-archive-keyring.gpg >/dev/null
curl -fsSL "https://pkgs.tailscale.com/stable/ubuntu/${CODENAME}.tailscale-keyring.list" \
  | sudo tee /etc/apt/sources.list.d/tailscale.list
sudo apt-get update
sudo apt-get install -y tailscale
sudo systemctl enable --now tailscaled
```

Cách ngắn hơn là script chính thức `curl -fsSL https://tailscale.com/install.sh | sh`; nên đọc script trước khi chạy.

Kiểm tra:

```bash
tailscale version
systemctl is-active tailscaled          # active
```

## 3. Đăng nhập server vào tailnet

Server không có trình duyệt nên dùng một trong hai cách.

**Cách A – URL đăng nhập:**

```bash
sudo tailscale up --hostname=corebank-server
```

Lệnh in ra URL `https://login.tailscale.com/a/...`; mở trên máy bất kỳ, đăng nhập tài khoản tailnet và duyệt máy.

**Cách B – Auth key** (admin console → Settings → Keys → *Generate auth key*, chọn *Pre-approved*, nếu đã có tag thì chọn `tag:server`):

```bash
sudo tailscale up --hostname=corebank-server --authkey='tskey-auth-XXXX'
```

Không lưu auth key vào repository, `.env`, lịch sử ticket hoặc chat. Có thể xóa lịch sử shell sau khi dùng (`history -d <số dòng>`) hoặc dùng key một lần (*not reusable*).

Không bật `--ssh`, `--advertise-exit-node` hay `--advertise-routes`; server chỉ cần là một node thường.

## 4. Giữ kết nối ổn định

- Admin console → **Machines** → `corebank-server` → ⋯ → **Disable key expiry**. Nếu không, node tự rớt khỏi tailnet sau thời hạn key (mặc định 180 ngày) và AI ngừng hoạt động.
- Làm tương tự cho Mac nếu muốn.
- `tailscaled` đã `enable` ở bước 2 nên tự chạy lại sau reboot.

## 5. Giới hạn quyền truy cập bằng ACL

Mặc định mọi node trong tailnet truy cập được lẫn nhau. Thu hẹp lại để chỉ server được gọi cổng `11434` của Mac:

1. Admin console → **Access controls**, thêm tag và rule (giữ các rule hiện có cho người dùng nếu cần):

   ```json
   {
     "tagOwners": {
       "tag:server": ["autogroup:admin"],
       "tag:llm":    ["autogroup:admin"]
     },
     "acls": [
       { "action": "accept", "src": ["tag:server"], "dst": ["tag:llm:11434"] }
     ]
   }
   ```

2. **Machines** → server → *Edit ACL tags* → `tag:server`; Mac → `tag:llm`.

Lưu ý: khi file ACL chỉ có rule trên, máy cá nhân sẽ không vào được server/Mac qua tailnet nữa. Nếu cần (ví dụ SSH qua tailnet), thêm rule riêng; SSH công khai port `26266` không bị ảnh hưởng.

## 6. Kiểm tra kết nối

Đặt IP Mac lấy ở bước 1:

```bash
MAC_IP=100.x.y.z
```

### 6.1 Tailnet

```bash
tailscale status                        # thấy corebank-server và Mac, Mac không "offline"
tailscale ping "$MAC_IP"                # "pong ... via <ip>:<port>" là kết nối trực tiếp; "via DERP" là qua relay
```

### 6.2 Ollama từ host server

```bash
curl -sS --max-time 5 "http://$MAC_IP:11434/api/version"
# {"version":"..."}

curl -sS "http://$MAC_IP:11434/api/tags" | grep -oE '"name":"[^"]+"'
# phải có qwen3:8b và bge-m3
```

Thử embedding (backend gọi `/api/embed`), kỳ vọng `1024`:

```bash
curl -sS "http://$MAC_IP:11434/api/embed" \
  -d '{"model":"bge-m3","input":["kiem tra ket noi"]}' \
  | python3 -c 'import json,sys; print(len(json.load(sys.stdin)["embeddings"][0]))'
```

Thử chat (backend gọi `/api/chat`); lần đầu có thể mất vài chục giây để nạp model:

```bash
time curl -sS "http://$MAC_IP:11434/api/chat" \
  -d '{"model":"qwen3:8b","stream":false,"think":false,"messages":[{"role":"user","content":"Trả lời đúng một từ: OK"}]}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["message"]["content"])'
```

Thử streaming (mỗi dòng là một JSON token):

```bash
curl -sS -N "http://$MAC_IP:11434/api/chat" \
  -d '{"model":"qwen3:8b","stream":true,"think":false,"messages":[{"role":"user","content":"Đếm từ 1 đến 5"}]}'
```

### 6.3 Ollama từ trong container backend

Backend chạy trong Docker; traffic container đi qua NAT của host nên thường tới được tailnet. Container **không phân giải được tên MagicDNS**, vì vậy luôn dùng IP `100.x.y.z`. Image runtime đã có `curl`:

```bash
cd /opt/core-academy-backend
docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml exec backend \
  curl -sS --max-time 5 "http://$MAC_IP:11434/api/version"
```

Nếu container chưa chạy, thử bằng một container tạm cùng mạng mặc định:

```bash
docker run --rm curlimages/curl:8.10.1 -sS --max-time 5 "http://$MAC_IP:11434/api/version"
```

### 6.4 Kiểm tra ACL (tùy chọn)

Từ một máy khác trong tailnet không có `tag:server`, lệnh `curl http://$MAC_IP:11434/api/version` phải timeout.

### Kết quả cần đạt

| Kiểm tra | Kỳ vọng |
|---|---|
| `tailscale status` | Server và Mac online |
| `/api/version` từ host | Trả JSON version trong < 1 giây |
| `/api/tags` | Có `qwen3:8b`, `bge-m3` |
| `/api/embed` | Độ dài vector `1024` |
| `/api/chat` | Trả nội dung, không lỗi model |
| `/api/version` từ container | Giống host |

## 7. Trỏ backend tới Mac

Thêm vào `.env.ubuntu`:

```bash
OLLAMA_BASE_URL=http://100.x.y.z:11434
# Chỉ đặt khi khác mặc định
# OLLAMA_CHAT_MODEL=qwen3:8b
# OLLAMA_EMBED_MODEL=bge-m3
```

`compose.ubuntu.yaml` và `deploy/ci/compose.ci.yaml` truyền `OLLAMA_*` và `KNOWLEDGE_*` vào container; tài liệu tải lên được lưu trong volume `backend_knowledge` vì container chạy `read_only`. Server deploy bằng CI đọc bản cài ở `/etc/core-academy-ci/compose.yaml`, nên cần cài lại `deploy/ci/compose.ci.yaml` vào đó (xem [CICD.md](CICD.md)) trước khi biến trong `.env.ubuntu` có tác dụng.

Recreate container và xem log khi gọi chat:

```bash
docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml up -d
docker compose --env-file .env.ubuntu -f compose.ubuntu.yaml logs -f backend | grep -i ollama
```

Log thành công có dạng `Ollama chat model=qwen3:8b messages=... durationMs=...`; prompt và nội dung tài liệu không được ghi log.

## 8. Lỗi thường gặp

| Lỗi | Kiểm tra |
|---|---|
| `tailscale up` treo | Server có ra Internet tới `login.tailscale.com` không; proxy/firewall outbound |
| Mac hiện `offline` | Mac đang ngủ, Tailscale trên Mac bị tắt hoặc đăng xuất |
| `curl` tới Mac timeout | ACL/tag sai; macOS Firewall chặn `ollama`; Ollama chưa đặt `OLLAMA_HOST=0.0.0.0` |
| `Connection refused` | Ollama không chạy, hoặc chỉ lắng nghe `127.0.0.1` |
| `model not found` | Chưa `ollama pull` đúng tên model trên Mac |
| Embedding khác `1024` | Sai `OLLAMA_EMBED_MODEL`; backend yêu cầu `bge-m3` |
| Host gọi được, container không | Container dùng tên MagicDNS thay IP; mạng Docker tùy chỉnh chặn ra ngoài; `iptables` FORWARD |
| `tailscale ping` luôn `via DERP` | UDP bị chặn; vẫn dùng được nhưng độ trễ cao hơn |
| Node rớt sau vài tháng | Chưa tắt key expiry (bước 4) |
| Tài liệu ingest bị đánh dấu lỗi | Mac offline quá lâu; worker chỉ thử lại `max-attempts: 3` |

## 9. Gỡ bỏ

```bash
sudo tailscale logout
sudo systemctl disable --now tailscaled
sudo apt-get remove -y tailscale
sudo rm -f /etc/apt/sources.list.d/tailscale.list /usr/share/keyrings/tailscale-archive-keyring.gpg
```

Sau đó xóa máy khỏi **Machines** trong admin console và bỏ `OLLAMA_BASE_URL` khỏi `.env.ubuntu`.

## Trạng thái kiểm tra

Tài liệu được viết theo cấu hình trong repository (`application.yml`, `OllamaClient`, `Dockerfile`, `compose.ubuntu.yaml`); chưa chạy các bước trên server hoặc Mac thực tế.
