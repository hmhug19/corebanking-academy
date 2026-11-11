# Phòng học 3D realtime

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
| Client → server | `dice-bet` | `{round,side,stake}`. `side` là `lower` hoặc `upper`, góp điểm nguyên từ 1 đến 1.000.000 CPoint. |
| Client → server | `wallet-rank` | Yêu cầu bảng CPoint. |
| Client → server | `caro-stake`, `caro-reply`, `caro-move` | Lần lượt dùng `{stake}`, `{inviteId,accept}`, `{gameId,move,cell}`. Người ngồi trước chọn mức góp từ 10 đến 1.000.000 CPoint mỗi bên. `move` là số nước đã đánh và `cell` là chỉ số ô 0–2499 theo thứ tự hàng. |
| Client → server | `approach-cancel` | Dừng tự đi tới mục tiêu. |
| Client → server | `seat` | `{action,chair?}`. `action` là `sit` hoặc `stand`. `chair` là chỉ số trong `objects`. Khi bỏ qua `chair`, server chọn ghế trống gần nhất có thể đến được. |
| Client → server | `high-five-invite` | Tìm người phù hợp gần nhất, tự đi tới tầm mời nếu cần. |
| Client → server | `high-five-reply` | `{inviteId,accept}`. Chỉ phiên nhận lời mời có quyền trả lời. |
| Client → server | `game-start` | Host mở câu hỏi kế tiếp trong bộ câu hỏi có sẵn |
| Client → server | `game-answer` | `{roundId,choice}`; `choice` là chỉ số từ 0 đến 3 |
| Client → server | `game-close` | `{roundId}`; host khóa câu hỏi và công bố đáp án |
| Client → server | `ping` | Heartbeat; FE gửi mỗi 10 giây |
| Server → client | `welcome` | `{id,players,history,gestures,interactions}`. Lịch sử chứa tối đa 50 tin nhắn của phòng trong phiên. Hai cờ xác nhận các tính năng phiên hỗ trợ. |
| Server → client | `joined` | `{player}`. Player gồm `id,name,color,avatar,x,y,facing,seat?`. |
| Server → client | `moved` | `{id,x,y,facing,seat?,approaching?,route?}`. `seat` chứa chỉ số ghế khi ngồi và `null` khi đứng. Tọa độ khi ngồi là điểm bước xuống ngay sau ghế. Khi bắt đầu tự đi, route có các điểm từ vị trí hiện tại và tốc độ theo px/giây. |
| Server → client | `correction` | `{x,y}`; vị trí sửa chỉ gửi cho người di chuyển không hợp lệ |
| Server → client | `chat` | `{id,playerId,name,color,text,at}`; `at` là ISO UTC |
| Server → client | `gesture` | `{kind,playerId,targetId?,facing?}`; vẫy chào quay về người gần nhất trong phạm vi 220 px nếu đường giữa hai người thông thoáng |
| Server → client | `wallet`, `wallet-rank` | `{balance}` gửi riêng và `{ranks:[{name,balance}]}` trả tối đa 10 người đang hoạt động theo số dư khả dụng. |
| Server → client | `dice-state` | `{round,closesAt,serverTime,history,bet?,lastBet?}`. Mốc giờ là epoch ms. Lịch sử tối đa 12 kết quả `{round,dice,total,outcome}` dùng chung. Hai trường cược chỉ gửi cho chính tài khoản. |
| Server → client | `dice-error` | `{message}`. Lỗi chọn cửa, số CPoint góp hoặc vị trí. |
| Server → client | `caro-table` | `{table,host,playing,paused,partner?,stake?}`. Khi rời ghế, event chỉ có `type`. |
| Server → client | `caro-invite`, `caro-invite-end` | `{id,from,fromName,to,toName,stake}` và `{id}`. Người ngồi sau đứng dậy nếu từ chối hoặc hết 20 giây chờ trả lời. |
| Server → client | `caro-state` | `{id,xName,oName,piece,stake,board,moves,lastCell?,expiresAt,serverTime,result?,timedOut?,timeout?,waitingForReturn?}`. Chuỗi bàn dài 2.500 ký tự `.`/`X`/`O`. Kết quả là `X`, `O` hoặc `draw`. `timeout` là `turn` hoặc `away`. Hạn lượt `expiresAt` vẫn chạy khi chờ người chơi kết nối lại. |
| Server → client | `caro-error` | `{message}`. Không thể gửi hoặc chấp nhận lời mời. |
| Server → client | `high-five-invite` | `{id,from,to}`. Chỉ gửi cho hai người tham gia. Lời mời có hiệu lực 10 giây. |
| Server → client | `high-five-end` | `{id}`. Hai bên đóng lời mời sau khi người nhận trả lời. |
| Server → client | `high-five` | `{from:{id,x,y},to:{id,x,y}}`. Cảnh 3D phát cùng một động tác từ tọa độ đã kiểm tra trên server. |
| Server → client | `game` | `{hostId,round?}`; gửi sau khi vào phòng và khi trạng thái Đố vui thay đổi. `round` gồm `{id,prompt,options,answered,yourChoice}` và thêm `{correctIndex,correctCount}` sau khi khóa câu hỏi |
| Server → client | `left` | `{id,name}` |
| Server → client | `error` | `{message,scope?}`; `scope: "games"` báo lỗi ví hoặc trò chơi. Các lỗi này không ngắt kết nối phòng. |
| Server → client | `pong` | Phản hồi heartbeat |

Hitbox AABB tại chân nhân vật; biên phòng, bàn, ghế, bảng, kệ và cây đều chặn di chuyển. Server kiểm tra đoạn đường với hitbox để chặn xuyên vật thể và ngân sách tốc độ để chặn teleport. Các nhân vật có thể đi ngang nhau. Hướng nhìn gồm `north`, `northeast`, `east`, `southeast`, `south`, `southwest`, `west`, `northwest`; client cũ bỏ qua field này vẫn hoạt động, server suy hướng từ dịch chuyển.

FE dùng Three.js để dựng camera 3D theo nhân vật. Kéo để xoay camera, bấm “Cận cảnh nhân vật” để nhìn từ phía trước. WASD hoặc phím mũi tên di chuyển theo góc camera. Khi tự đi tới mục tiêu, server chọn đường khả dụng và gửi các điểm trên đường. Client diễn hoạt ở từng khung hình và nhận lại trạng thái xác nhận khi tới đích.

Bản đồ 3.200 × 2.240 có `activityArea` đánh dấu khu giao lưu có thể đi qua. Bấm Space khi cảnh được focus hoặc bấm “Nhảy” để nhảy tại chỗ. “Vẫy chào” vẫy về phía người ở gần hoặc vẫy tại chỗ. Ghế và đập tay dùng đường đi do server tính nếu còn xa. Server kiểm tra lại chỗ ngồi và đối tác lúc tới nơi. Mục tiêu không phù hợp thì thao tác dừng im lặng. Ghế được giải phóng khi người đó đứng dậy hoặc rời phòng.

Nhấn Enter hoặc “Nhắn tin” trong khung trò chuyện đè trên cảnh 3D. Khung hiện tối đa 50 tin gần nhất trong phiên, gồm cả tin đã gửi trước khi người dùng vào phòng. Bong bóng trên đầu nhân vật tồn tại 5,5 giây. Server giữ tin trong RAM và xóa lịch sử khi người cuối cùng rời phòng.

Tài khoản nhận 50 CPoint ở lần vào phòng đầu tiên mỗi ngày theo múi giờ Việt Nam. Mỗi dòng XP dương mới trong sổ XP cộng cùng số CPoint, cùng giao dịch với khoản XP. Trên/Dưới tạo lượt 30 giây khi có người mở bàn. Mỗi tài khoản được đặt một cược một lượt. Server tung ba viên một lần, lưu kết quả và trả gấp đôi tiền đặt cho cửa thắng. Dưới là 4–10, Trên là 11–17, bộ ba làm cả hai cửa thua. Rời bàn vẫn nhận kết toán cho cược đã đặt.

HTTP 101 xác nhận WebSocket đã mở. Sự kiện `welcome` xác nhận người dùng đã vào phòng và được gửi trước khi đọc ví. Lần đầu vào phòng, máy chủ tạo ví còn thiếu và ghi ngày điểm danh cùng khoản 50 CPoint trong một thao tác. Nếu cơ sở dữ liệu trò chơi lỗi, người dùng vẫn có thể vào phòng và trò chuyện.

Ba bàn Caro 50×50 cạnh Trên/Dưới đều có hai ghế đối diện. Cả hai phải ngồi cùng bàn để chơi. Hai người góp cùng số CPoint, ít nhất 10 mỗi bên. Máy chủ giữ số điểm góp trong RAM khi cả hai đồng ý. Từ 6 quân liên tiếp thì thắng. Đúng 5 quân cần ít nhất một đầu trống, mép bàn tính là đầu chặn. Mỗi lượt có 120 giây. Khi có người rời phòng, đồng hồ lượt tiếp tục chạy. Người rời có thể quay lại bằng cùng tài khoản và ngồi cùng bàn trước khi lượt hết giờ. Nếu hết giờ trong lúc có người vắng mặt, người rời trước tiên thua. Ván hòa giải phóng toàn bộ điểm đang giữ. Ván có kết quả chỉ ghi giao dịch chuyển CPoint vào ví. Bàn cờ, nước đi và lịch sử ván nằm trong RAM của một instance.

## Cấu hình và triển khai

Khi nâng cấp từ bản lưu Caro trong DB, dừng backend cũ rồi chạy lại [migration ví và trò chơi](../sql/upgrade/2026-11-09-chat-room-games.sql) bằng tài khoản sở hữu bảng. File hoàn CPoint đã trừ của các ván cũ chưa kết thúc và thêm mã chống ghi trùng giao dịch vào ví. Sau đó triển khai BE rồi FE. Khởi động lại backend xóa phiên trong RAM. CPoint đang giữ của ván chưa chốt trở lại khả dụng trong ví.

Nếu role chạy migration khác `DB_USER` của backend, DBA cấp quyền trên ba bảng trong đúng database.
Thay `"TEN_DB_USER"` bằng giá trị `DB_USER` của container backend:

```sql
BEGIN;
GRANT SELECT, INSERT, UPDATE ON
    learning_hub.chat_room_wallets,
    learning_hub.chat_room_dice_rounds TO "TEN_DB_USER";
GRANT SELECT, INSERT ON learning_hub.chat_room_dice_bets TO "TEN_DB_USER";
COMMIT;
```

Log `Chat room game storage unavailable` với `sqlState=42501` báo thiếu quyền. Sau khi cấp quyền,
người dùng rời phòng rồi vào lại để backend tạo ví còn thiếu và ghi nhận điểm danh.

Các property có mặc định tập trung tại `application.yml` và được validate bởi `ChatRoomProperties`:

| Property | Mặc định |
|---|---|
| `app.chat-room.ticket-ttl` | `20s` |
| `app.chat-room.idle-timeout` | `60s` |
| `app.chat-room.session-check-interval` | `10s` |
| `app.chat-room.send-timeout` | `2s` |

FE dev proxy `/api` đã bật `ws: true`. Reverse proxy phục vụ FE cần chuyển tiếp `/api/v1/chat-room/socket` tới cùng backend với `Upgrade`, `Connection: upgrade`, HTTP/1.1 và timeout trên 35 giây; mẫu trong `deploy/nginx/core-academy.conf.example`. Origin FE phải có trong cấu hình CORS hiện hành. Không deploy tự động khi sửa source.

Chạy `mvn clean verify` và các kiểm tra FE hiện có. Nếu máy không có Docker, các bài Testcontainers không đủ điều kiện chạy; build/package không thay thế kiểm thử có database thật.
