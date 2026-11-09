# Phòng học 3D/2D realtime

Module `chatroom` trong backend hiện có, FE tương ứng là route `/chat-room` trong `lms-vip-pro-max`. Mọi tài khoản ACTIVE đã đổi mật khẩu tạm đều được vào phòng. Ví và các khoản cược gắn với tài khoản đăng nhập.

## HTTP và xác thực

- `GET /api/v1/chat-room/map`: yêu cầu JWT như API khác, trả `BaseResponse<Layout>`. Bản đồ/hitbox từ `src/main/resources/chat-room/room.json`, cùng được dùng để vẽ và kiểm tra va chạm phía FE.
- `POST /api/v1/chat-room/tickets`: yêu cầu JWT, kiểm tra status/auth_ver và mật khẩu tạm; trả `BaseResponse<{ticket,expiresAt}>`. Vé mặc định có hạn 20 giây và bị tiêu thụ đúng một lần. Không dùng JWT trong URL WebSocket.
- `GET /api/v1/chat-room/socket?ticket=…`: HTTP Upgrade, handshake interceptor xác thực vé và đối chiếu lại phiên. Chỉ endpoint socket được bỏ qua bearer filter; không có vé hợp lệ sẽ nhận 401. Origin phải nằm trong `app.cors.origins` hiện có. Không ghi query vé vào access log.
- Server kiểm tra lại tài khoản/status/auth_ver định kỳ và hết hạn JWT; logout, khóa tài khoản hoặc thu hồi phiên sẽ đóng socket. Mã close 1008 báo phiên/chính sách không hợp lệ, 1013 báo phòng đầy. FE không tự retry các mã này; người dùng có thể thử lại và lấy vé mới qua API đã xác thực.

## Packet WebSocket

Đây là luồng event, không bọc `BaseResponse`. HTTP cấp vé/bản đồ và lỗi HTTP vẫn theo envelope chuẩn.

| Hướng | type | Nội dung |
|---|---|---|
| Client → server | `join` | `{name,color,avatar?,activities?,gestures?,interactions?,roomGames?}`. Tên dài 1–24 ký tự sau chuẩn hóa. Màu nhận `teal/slate/amber/blue/rose`. Mẫu nhân vật nhận `male` hoặc `female`, mặc định `male`. `roomGames: true` bật ví, Trên/Dưới và Caro. |
| Client → server | `move` | `{x,y,facing?}`; tọa độ chân nhân vật theo pixel; `facing` là một trong tám hướng |
| Client → server | `chat` | `{text}`; 1–280 ký tự, tối thiểu 750 ms giữa các tin |
| Client → server | `gesture` | `{kind}` với `jump` hoặc `wave`; server nhận tối đa một động tác mỗi 1.200 ms cho mỗi kết nối |
| Client → server | `dice-open`, `dice-close` | Đăng ký hoặc dừng nhận các lượt Trên/Dưới. Mở bàn yêu cầu người chơi ở trong `diceArea`. |
| Client → server | `dice-bet` | `{round,side,stake}`. `side` là `lower` hoặc `upper`, cược nguyên từ 1 đến 1.000.000 token. |
| Client → server | `wallet-rank` | Yêu cầu bảng Top tài phú. |
| Client → server | `caro-invite`, `caro-reply`, `caro-move` | Lần lượt dùng `{target,stake}`, `{inviteId,accept}`, `{gameId,move,cell}`. `target` là ID phiên người được mời, `move` là số nước đã đánh và `cell` là chỉ số ô 0–2499 theo thứ tự hàng. |
| Client → server | `approach-cancel` | Dừng tự đi tới mục tiêu. |
| Client → server | `seat` | `{action,chair?}`. `action` là `sit` hoặc `stand`. `chair` là chỉ số trong `objects`. Khi bỏ qua `chair`, server chọn ghế trống gần nhất có thể đến được. |
| Client → server | `high-five-invite` | Tìm người phù hợp gần nhất, tự đi tới tầm mời nếu cần. |
| Client → server | `high-five-reply` | `{inviteId,accept}`. Chỉ phiên nhận lời mời có quyền trả lời. |
| Client → server | `game-start` | Host mở câu hỏi kế tiếp trong bộ câu hỏi có sẵn |
| Client → server | `game-answer` | `{roundId,choice}`; `choice` là chỉ số từ 0 đến 3 |
| Client → server | `game-close` | `{roundId}`; host khóa câu hỏi và công bố đáp án |
| Client → server | `ping` | Heartbeat; FE gửi mỗi 10 giây |
| Server → client | `welcome` | `{id,players,history:[],gestures,interactions}`. Hai cờ xác nhận các tính năng phiên hỗ trợ. |
| Server → client | `joined` | `{player}`. Player gồm `id,name,color,avatar,x,y,facing,seat?`. |
| Server → client | `moved` | `{id,x,y,facing,seat?,approaching?}`. `seat` chứa chỉ số ghế khi ngồi và `null` khi đứng. Tọa độ khi ngồi là điểm bước xuống ngay sau ghế. |
| Server → client | `correction` | `{x,y}`; vị trí sửa chỉ gửi cho người di chuyển không hợp lệ |
| Server → client | `chat` | `{id,playerId,name,color,text,at}`; `at` là ISO UTC |
| Server → client | `gesture` | `{kind,playerId,targetId?,facing?}`; vẫy chào quay về người gần nhất trong phạm vi 220 px nếu đường giữa hai người thông thoáng |
| Server → client | `wallet`, `wallet-rank` | `{balance}` gửi riêng và `{ranks:[{name,balance}]}` trả tối đa 10 người đang hoạt động theo số dư khả dụng. |
| Server → client | `dice-state` | `{round,closesAt,serverTime,history,bet?,lastBet?}`. Mốc giờ là epoch ms. Lịch sử tối đa 12 kết quả `{round,dice,total,outcome}` dùng chung. Hai trường cược chỉ gửi cho chính tài khoản. |
| Server → client | `dice-error` | `{message}`. Lỗi chọn cửa, số token đặt hoặc vị trí. |
| Server → client | `caro-invite`, `caro-invite-end` | `{id,from,fromName,to,toName,stake}` và `{id}`. Lời mời có hiệu lực 20 giây. |
| Server → client | `caro-state` | `{id,xName,oName,piece,stake,board,moves,expiresAt,serverTime,result?}`. Chuỗi bàn dài 2.500 ký tự `.`/`X`/`O`. Kết quả là `X`, `O` hoặc `draw`. |
| Server → client | `caro-error` | `{message}`. Không thể gửi hoặc chấp nhận lời mời. |
| Server → client | `high-five-invite` | `{id,from,to}`. Chỉ gửi cho hai người tham gia. Lời mời có hiệu lực 10 giây. |
| Server → client | `high-five-end` | `{id}`. Hai bên đóng lời mời sau khi người nhận trả lời. |
| Server → client | `high-five` | `{from:{id,x,y},to:{id,x,y}}`. Hai renderer phát cùng một động tác từ tọa độ đã kiểm tra trên server. |
| Server → client | `game` | `{hostId,round?}`; gửi sau khi vào phòng và khi trạng thái Đố vui thay đổi. `round` gồm `{id,prompt,options,answered,yourChoice}` và thêm `{correctIndex,correctCount}` sau khi khóa câu hỏi |
| Server → client | `left` | `{id,name}` |
| Server → client | `error` | `{message}`; lỗi packet/validation, không phản chiếu thông tin nội bộ |
| Server → client | `pong` | Phản hồi heartbeat |

Hitbox AABB tại chân nhân vật; biên phòng, bàn, ghế, bảng, kệ và cây đều chặn di chuyển. Server kiểm tra đoạn đường mỗi 4 px để chặn xuyên vật thể và ngân sách tốc độ để chặn teleport. Các nhân vật có thể đi ngang nhau. Hướng nhìn gồm `north`, `northeast`, `east`, `southeast`, `south`, `southwest`, `west`, `northwest`; client cũ bỏ qua field này vẫn hoạt động, server suy hướng từ dịch chuyển.

FE mặc định dùng Three.js để dựng camera 3D theo nhân vật. Kéo để xoay camera, bấm “Cận cảnh nhân vật” để nhìn từ phía trước. WASD hoặc phím mũi tên di chuyển theo góc camera. Có thể chuyển sang bản đồ Phaser 2D trong cùng phiên WebSocket. Hai renderer dùng chung tọa độ, hitbox và hàm di chuyển.

Bản đồ 3.200 × 2.240 có `activityArea` đánh dấu khu giao lưu có thể đi qua. Bấm Space khi cảnh được focus hoặc bấm “Nhảy” để nhảy tại chỗ. “Vẫy chào” vẫy về phía người ở gần hoặc vẫy tại chỗ. Ghế và đập tay dùng đường đi do server tính nếu còn xa. Server kiểm tra lại chỗ ngồi và đối tác lúc tới nơi. Mục tiêu không phù hợp thì thao tác dừng im lặng. Ghế được giải phóng khi người đó đứng dậy hoặc rời phòng.

Nhấn Enter hoặc “Nhắn tin” để mở ô nhập dưới cảnh, ngay trên dòng hướng dẫn. Enter gửi và đóng ô. Esc đóng ô để tiếp tục di chuyển. Tin nhắn chỉ thay tên trên đầu nhân vật trong 5,5 giây rồi biến mất. Không có khung trò chuyện/lịch sử. Backend không lưu nội dung tin nhắn; chỉ broadcast tới người đã vào phòng tại thời điểm gửi. Người vào sau hoặc reconnect không nhận tin cũ, kể cả khi bong bóng còn hiện ở client khác.

Tài khoản nhận 50 token ở lần vào phòng đầu tiên mỗi ngày theo múi giờ Việt Nam. Mỗi dòng XP dương mới trong sổ XP cộng cùng số token, cùng giao dịch với khoản XP. Trên/Dưới tạo lượt 30 giây khi có người mở bàn. Mỗi tài khoản được đặt một cược một lượt. Server tung ba viên một lần, lưu kết quả và trả gấp đôi tiền đặt cho cửa thắng. Dưới là 4–10, Trên là 11–17, bộ ba làm cả hai cửa thua. Rời bàn vẫn nhận kết toán cho cược đã đặt.

Caro dùng bàn 50×50. Người gửi và người nhận phải ở gần lúc mời và lúc đồng ý. Khi đồng ý, server giữ cùng mức cược mỗi bên (0–1.000.000 token). Chuỗi đúng 5 quân với ít nhất một đầu trống thắng ván. Mép bàn được tính là đầu chặn. Đầy bàn hoặc 120 giây không có nước hợp lệ thì hòa và hoàn cược. Bàn và cược được lưu, nên người chơi có thể kết nối lại trước khi hết thời gian.

## Cấu hình và triển khai

Chạy [migration ví và trò chơi](../sql/upgrade/2026-11-09-chat-room-games.sql) sau khi đã có sổ XP và trước khi triển khai BE rồi FE. Giữ Java/compiler và Docker theo cấu hình repository. Vị trí, kết nối và lời mời hiện tại thuộc một instance, tối đa 60 kết nối. Khi reconnect, nhân vật xuất hiện lại ở lối vào. Ví, kết quả Trên/Dưới và ván Caro lưu trong PostgreSQL.

Các property có mặc định tập trung tại `application.yml` và được validate bởi `ChatRoomProperties`:

| Property | Mặc định |
|---|---|
| `app.chat-room.ticket-ttl` | `20s` |
| `app.chat-room.idle-timeout` | `60s` |
| `app.chat-room.session-check-interval` | `10s` |
| `app.chat-room.send-timeout` | `2s` |

FE dev proxy `/api` đã bật `ws: true`. Reverse proxy phục vụ FE cần chuyển tiếp `/api/v1/chat-room/socket` tới cùng backend với `Upgrade`, `Connection: upgrade`, HTTP/1.1 và timeout trên 35 giây; mẫu trong `deploy/nginx/core-academy.conf.example`. Origin FE phải có trong cấu hình CORS hiện hành. Không deploy tự động khi sửa source.

Chạy `mvn clean verify` và các kiểm tra FE hiện có. Nếu máy không có Docker, các bài Testcontainers không đủ điều kiện chạy; build/package không thay thế kiểm thử có database thật.
