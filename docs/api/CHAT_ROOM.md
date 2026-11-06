# Phòng học 3D/2D realtime

Module `chatroom` trong backend hiện có, FE tương ứng là route `/chat-room` trong `lms-vip-pro-max`. Mọi tài khoản ACTIVE đã đổi mật khẩu tạm đều được vào phòng; tên hiển thị chỉ là nhãn, không thay thế danh tính đăng nhập. Không thêm bảng, seed, migration hoặc quyền quản trị.

## HTTP và xác thực

- `GET /api/v1/chat-room/map`: yêu cầu JWT như API khác, trả `BaseResponse<Layout>`. Bản đồ/hitbox từ `src/main/resources/chat-room/room.json`, cùng được dùng để vẽ và kiểm tra va chạm phía FE.
- `POST /api/v1/chat-room/tickets`: yêu cầu JWT, kiểm tra status/auth_ver và mật khẩu tạm; trả `BaseResponse<{ticket,expiresAt}>`. Vé mặc định có hạn 20 giây và bị tiêu thụ đúng một lần. Không dùng JWT trong URL WebSocket.
- `GET /api/v1/chat-room/socket?ticket=…`: HTTP Upgrade, handshake interceptor xác thực vé và đối chiếu lại phiên. Chỉ endpoint socket được bỏ qua bearer filter; không có vé hợp lệ sẽ nhận 401. Origin phải nằm trong `app.cors.origins` hiện có. Không ghi query vé vào access log.
- Server kiểm tra lại tài khoản/status/auth_ver định kỳ và hết hạn JWT; logout, khóa tài khoản hoặc thu hồi phiên sẽ đóng socket. Mã close 1008 báo phiên/chính sách không hợp lệ, 1013 báo phòng đầy. FE không tự retry các mã này; người dùng có thể thử lại và lấy vé mới qua API đã xác thực.

## Packet WebSocket

Đây là luồng event, không bọc `BaseResponse`. HTTP cấp vé/bản đồ và lỗi HTTP vẫn theo envelope chuẩn.

| Hướng | type | Nội dung |
|---|---|---|
| Client → server | `join` | `{name,color}`; tên 1–24 ký tự sau chuẩn hóa NFC/trim; màu `teal/slate/amber/blue/rose` |
| Client → server | `move` | `{x,y,facing?}`; tọa độ chân nhân vật theo pixel; `facing` là một trong tám hướng |
| Client → server | `chat` | `{text}`; 1–280 ký tự, tối thiểu 750 ms giữa các tin |
| Client → server | `ping` | Heartbeat; FE gửi mỗi 10 giây |
| Server → client | `welcome` | `{id,players,history:[]}`; danh sách người chơi, không phát lại tin nhắn. `history` rỗng chỉ giữ tương thích client cũ |
| Server → client | `joined` | `{player}`; player gồm `id,name,color,x,y,facing` |
| Server → client | `moved` | `{id,x,y,facing}`; vị trí đã qua kiểm tra tốc độ và đường đi |
| Server → client | `correction` | `{x,y}`; vị trí sửa chỉ gửi cho người di chuyển không hợp lệ |
| Server → client | `chat` | `{id,playerId,name,color,text,at}`; `at` là ISO UTC |
| Server → client | `left` | `{id,name}` |
| Server → client | `error` | `{message}`; lỗi packet/validation, không phản chiếu thông tin nội bộ |
| Server → client | `pong` | Phản hồi heartbeat |

Hitbox AABB tại chân nhân vật; biên phòng, bàn, ghế, bảng, kệ và cây đều chặn di chuyển. Server kiểm tra đoạn đường mỗi 4 px để chặn xuyên vật thể và ngân sách tốc độ để chặn teleport. Các nhân vật có thể đi ngang nhau. Hướng nhìn gồm `north`, `northeast`, `east`, `southeast`, `south`, `southwest`, `west`, `northwest`; client cũ bỏ qua field này vẫn hoạt động, server suy hướng từ dịch chuyển.

FE mặc định dùng Three.js để dựng góc nhìn thứ nhất 3D, có thể chuyển sang bản đồ Phaser 2D ngay trong phòng mà không kết nối lại. Hai renderer dùng chung tọa độ, hitbox và hàm di chuyển. Chế độ 3D xoay bằng chuột khi nhấp vào phòng, kéo trên màn hình cảm ứng; WASD/phím mũi tên di chuyển tương đối theo hướng nhìn. Chế độ 2D di chuyển theo bản đồ và quay mặt theo tám hướng.

Nhấn Enter hoặc “Nhắn tin” để mở ô nhập dưới cảnh, ngay trên dòng hướng dẫn. Enter gửi và đóng ô; Esc đóng ô hoặc thả chuột. Tin nhắn chỉ thay tên trên đầu nhân vật trong 5,5 giây rồi biến mất. Không có khung trò chuyện/lịch sử. Backend không lưu nội dung tin nhắn; chỉ broadcast tới người đã vào phòng tại thời điểm gửi. Người vào sau hoặc reconnect không nhận tin cũ, kể cả khi bong bóng còn hiện ở client khác.

## Cấu hình và triển khai

Giữ Java/compiler, Docker và database theo cấu hình repository. Dependency BE là `spring-boot-starter-websocket`, theo BOM hiện có. Mỗi instance giữ một phòng và tối đa 60 kết nối trong RAM; không có bộ đệm lịch sử chat. Khi reconnect, nhân vật xuất hiện lại ở lối vào. Chưa hỗ trợ chia sẻ phòng giữa nhiều replica. Deploy BE trước FE để ngừng giữ/phát lịch sử cả ở lớp giao thức; không chỉ ẩn lịch sử ở giao diện. Không cần migration database hoặc thay cấu hình Nginx đang hoạt động.

Các property có mặc định tập trung tại `application.yml` và được validate bởi `ChatRoomProperties`:

| Property | Mặc định |
|---|---|
| `app.chat-room.ticket-ttl` | `20s` |
| `app.chat-room.idle-timeout` | `60s` |
| `app.chat-room.session-check-interval` | `10s` |
| `app.chat-room.send-timeout` | `2s` |

FE dev proxy `/api` đã bật `ws: true`. Reverse proxy phục vụ FE cần chuyển tiếp `/api/v1/chat-room/socket` tới cùng backend với `Upgrade`, `Connection: upgrade`, HTTP/1.1 và timeout trên 35 giây; mẫu trong `deploy/nginx/core-academy.conf.example`. Origin FE phải có trong cấu hình CORS hiện hành. Không deploy tự động khi sửa source.

Chạy `mvn clean verify` và các kiểm tra FE hiện có. Nếu máy không có Docker, các bài Testcontainers không đủ điều kiện chạy; build/package không thay thế kiểm thử có database thật.
