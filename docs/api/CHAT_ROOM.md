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

### Nâng cấp Khu vui chơi ngày 17-11-2026

Điều chỉnh hiện hành: sàn đổi màu đã được gỡ khỏi map (`colorFloor: null`, `colorTiles: []`),
server không quảng bá capability hoặc chạy vòng sàn màu nữa. Các mô tả/fixture sàn bên dưới
ghi lại bản trước của nâng cấp. Ba nhóm cờ được xếp ba hàng cách nhau 240 px ở góc Trên/Dưới.
Menu “Thách đấu trí tuệ” gom ba bàn Caro, sáu bàn cờ và Trên/Dưới. Lệnh `dice-approach`
đưa người chơi theo đường hợp lệ tới vùng Trên/Dưới, sau đó FE mới gửi `dice-open`.
Chọn Caro từ menu tự chọn ghế trống của đúng bàn. Các thay đổi này không cần migration DB.

Nâng cấp này không có migration, không tạo bảng/cột và không cần cấp thêm quyền DB.
Sáu bàn cờ, nước đi, đồng hồ, lời mời hòa, trạng thái sàn màu và reaction nằm trong RAM của
một backend instance. Cờ vua/cờ tướng dùng cơ chế giữ CPoint và kết toán ví hiện có của Caro
qua `ChatRoomGames.settleCaro`. Không đọc/ghi DB theo từng nước cờ. Khởi động lại backend
xóa các ván chưa kết thúc và giải phóng phần CPoint đang giữ. Ví, điểm danh, XP và Trên/Dưới
tiếp tục dùng các bảng hiện có. Các bước migration cũ bên dưới chỉ dành cho cài đặt chưa có
schema ví đã triển khai trước đây.

Capability bổ sung trong `join` là `reactions`, `boardGames`, `floorGame`. Server xác nhận
bằng `playground-ready` và chỉ gửi từng nhóm event mới cho client có capability tương ứng.
Client cũ không cần hiểu các event này. Tọa độ sáu bàn và sàn nằm trong cùng `room.json`;
bàn/ghế có hitbox, các ô sàn đi qua được. Đồng hồ mặc định lấy từ `boardClock` trong map.

| Lệnh/event | Hợp đồng bổ sung |
|---|---|
| `reaction` hai chiều | Client `{reaction}`; server `{reaction,playerId}`. ID `hello/laugh/clap/love/celebrate/surprise`, tối thiểu 1.200 ms/lần. Không nằm trong lịch sử chat. |
| `board-watch` | `{table}`; đăng ký xem snapshot, không cấp quyền chơi. |
| `board-ready` | `{table,points}`; phải ngồi đúng bàn, hai tài khoản khác nhau cùng sẵn sàng, góp bằng nhau từ 10 CPoint. |
| `board-move` | `{table,matchId,version,from,to,promotion?}`. Ô đánh số từ góc trái trên, theo hàng; `promotion` là `q/r/b/n` khi phong cấp. |
| `board-action` | `{table,matchId,version,action,from?,to?,promotion?}` với `resign/offer-draw/accept-draw/claim-draw`. Các ô tùy chọn là nước dự định xin hòa. |
| `board-tables` | `{tables:[{id,status,players}]}`; trạng thái `empty/waiting/playing`. |
| `board-state` | Bàn, ghế, hai trạng thái sẵn sàng, mức góp, cấu hình đồng hồ và `match?`. Match gồm ID, version, board, side, turn, clocks, serverTime, legal, lastCells, check, history, result, reason, drawOffer, mustChange. |
| `board-error` | `{message}` và snapshot mới khi lệnh trùng/sai phiên bản/nước không hợp lệ. |
| `floor-join` | Không có payload; phải đứng trong vùng sàn và có capability. Người đến giữa ván xếp hàng ván sau. |
| `floor-state` | `{id,phase,round,target,deadline,serverTime,players,queued,joined,alive,eliminated,winners}`; snapshot riêng theo tài khoản. |

Mọi nước được xác minh dưới cùng monitor của phòng trước khi đổi lượt. Đồng hồ 5 phút mỗi bên,
cộng 3 giây sau nước hợp lệ, vẫn chạy khi mất mạng hoặc đứng dậy. Cùng tài khoản kết nối lại
nhận snapshot và trở lại ghế cũ. Người xem không được gửi nước. Kết quả giữ tối đa 120 giây
sau khi kết toán; không xóa giao dịch đang chờ xử lý lỗi DB.

#### Bộ luật và giới hạn đã biết

Cờ vua đối chiếu [FIDE Laws of Chess, hiệu lực 01-01-2023](https://handbook.fide.com/chapter/e012023),
điều 3, 5, 6, 9. Có nước hợp lệ/gỡ chiếu/ghim, nhập thành, bắt qua đường, bốn lựa chọn phong cấp,
chiếu hết, hết nước, xin hòa, đầu hàng, hết giờ. Engine vẫn hỗ trợ lệnh yêu cầu hòa ba lần/50 nước
và nước dự định để tương thích; giao diện đã bỏ hai nút xác minh hòa theo yêu cầu sản phẩm.
Giao diện chỉ giữ mời hòa/đồng ý hòa và đầu hàng. Yêu cầu đúng không thực hiện nước đó.
Yêu cầu sai cộng 2 phút cho đối phương và thực hiện nước dự định hợp lệ. Năm lần/75 nước tự hòa,
ưu tiên chiếu hết. Khóa thế gồm bên đi, quyền nhập thành và bắt qua đường thực sự hợp lệ.
**Giới hạn:** nhận diện thế chết/khả năng chiếu hết hiện dựa trên các nhóm vật chất thông dụng,
chưa giải bài toán tồn tại chuỗi nước hợp lệ cho mọi thế khóa tốt hoặc thế chết đặc biệt.
Vì vậy không công bố tuân thủ FIDE đầy đủ cho mọi thế chết/hết giờ/đầu hàng.

Cờ tướng đối chiếu [World Xiangqi Rules 2018](https://www.wxf-xiangqi.org/images/wxf-rules/2018_World_XiangQi_Rules_English2018.pdf).
Có nước của bảy loại quân, cung/sông, cản mã, mắt tượng, ngòi pháo, tướng nhìn mặt, gỡ chiếu;
hết nước là thua. Bộ phân loại chu kỳ ưu tiên chiếu dai trên đuổi dai, theo danh tính quân bị đuổi,
loại đuổi bằng tướng/tốt, tốt chưa qua sông, đổi quân cùng loại và quân có bảo vệ hợp lệ;
mã/pháo đuổi xe có ngoại lệ. Một bên vi phạm phải đổi nước; tiếp tục chu kỳ sau cảnh báo thì thua.
Hai bên cùng mức vi phạm hòa. Lặp không vi phạm cần bốn lần xuất hiện theo 3.2.B.
Bộ đếm tự xác minh sau 100 lượt đơn không bắt quân
(50 nước mỗi bên); chỉ 10 lần chiếu đầu tiên mỗi bên tính vào bộ đếm, chiếu dư không tăng số đếm.
Bắt quân đặt lại bộ đếm. Yêu cầu sai trừ 5 phút và một lỗi kỹ thuật, lỗi thứ hai hoặc hết giờ thì thua.
**Giới hạn:** mới có fixture hình 1 và các fixture luật tổng quát; chưa chứng nhận đủ 110 hình WXF.
Các tổ hợp đuổi/đổi quân/bảo vệ thật giả nhiều quân, nhất là hình 39–41 và 108, vẫn cần bổ sung
fixture và đối chiếu. Không công bố engine WXF đầy đủ.

Sàn màu miễn phí, đếm ngược 5 giây khi đủ hai người, tối đa năm vòng 5 giây. Server chốt bằng
tọa độ chân đã chấp nhận trước deadline. Ô dùng biên nửa mở `[x,x+w) × [y,y+h)`;
mép trái/trên thuộc ô, phải/dưới thuộc ô kế tiếp, mép ngoài phải/dưới nằm ngoài sàn.
Server chốt vòng trước khi nhận gói đi đến muộn, chỉ chốt một lần. Mất mạng được chấm theo
sự hiện diện/tọa độ tại hạn; nhiều người còn lại vòng cuối đồng thắng, không còn ai thì không có người thắng.

Fixture và lệnh kiểm thử:

| File | Phạm vi |
|---|---|
| `RoomBoardPositionTest` | Chess perft 20/400/8902; nhập thành, bắt qua đường bị ghim, khóa thế, phong cấp; vật chất; bảo vệ pháo thật/giả sau nước bắt, nước cờ tướng và đường tới cả 12 ghế mới. |
| `RoomBoardMatchTest` | Đúng người/version/deadline; ưu tiên chiếu hết, hòa 3/5 lần và 50/75 nước, nước xin hòa dự định, phạt xin hòa sai; WXF hình 1, lặp không vi phạm, đếm 100 lượt. |
| `ChatRoomPlaygroundTest` | Sáu ván độc lập, hai người sẵn sàng, người xem, hai gói đồng thời, reconnect, capability/rate reaction, emoji ghép và lịch sử. |
| `RoomColorFloorTest` | Mép ô, hai người, năm vòng, đồng thắng, tất cả trượt, hàng chờ ván sau. |

Chạy `mvn test -Dtest=RoomBoardPositionTest,RoomBoardMatchTest,ChatRoomPlaygroundTest,RoomColorFloorTest`.

#### Bàn trình chiếu và màn hình 16:9

Không thêm bảng, cột hay migration. `presentation` trong map trỏ tới bảng 0, bàn 73, ghế 74.
Khách gửi capability `presentation: true`; chỉ người đang ngồi ghế trình chiếu được điều khiển.
Ảnh/video từ máy phát qua WebRTC trực tiếp tới từng người xem, không tải tệp lên backend,
không ghi hình và không lưu byte media trong bộ nhớ backend. Trình duyệt vẫn cần bộ đệm giải mã
và khung hình; ảnh dựng canvas 1920×1080, video đọc dần bằng object URL. YouTube dùng IFrame API,
mỗi người tải trực tiếp từ YouTube theo cùng thời điểm/tạm dừng. Người xem bật âm thanh bằng thao tác.

| Lệnh/event | Hợp đồng |
|---|---|
| `media-state` | `{id,controller,name,source,title,videoId,paused,position,updatedAt,serverTime,iceServers}`. ID phiên UUID v4; source `idle/image/video/screen/youtube`. |
| `media-start` | `{id,source,title?,videoId?}` từ người ngồi ghế; YouTube chỉ nhận ID 11 ký tự, không nhận URL iframe tùy ý. |
| `media-stop` | Người trình chiếu dừng và đổi ID phiên. |
| `media-control` | `{id,paused,position}` cho YouTube; chỉ chủ ghế, giới hạn 100 ms/lệnh. |
| `media-watch` | `{id,connection}` từ người xem; connection UUID v4 mới cho mỗi kết nối. |
| `media-peer` | `{id,peer,connection}` gửi chủ ghế để tạo offer. |
| `media-signal` | Client `{id,connection,to,kind,payload}`, server thay `to` bằng `from`. `offer/answer/ice`, tối đa 20.000 ký tự payload. Chỉ relay giữa chủ ghế và người xem đã đăng ký đúng phiên/kết nối. |
| `media-error` | `{message}` có ngữ cảnh quyền/trạng thái. |

Đứng dậy/mất kết nối dừng phiên. Chủ ghế mới luôn bắt đầu phiên mới; callback rời phòng muộn của
chủ cũ không xóa phiên của người mới. FE dọn tracks, peers, timers, iframe và object URLs khi đổi
nguồn/rời phòng. Người vào muộn xin kết nối mới. Metadata và tín hiệu tồn tại trong RAM một instance.
Kích thước packet tối đa 32 KiB để chứa SDP; giới hạn payload phía trên vẫn áp dụng.

| Biến môi trường | Mặc định / ý nghĩa |
|---|---|
| `CHAT_ROOM_ICE_URLS` | `stun:stun.l.google.com:19302`; danh sách STUN/TURN phân cách dấu phẩy. |
| `CHAT_ROOM_TURN_USERNAME` | Rỗng; tài khoản TURN nếu dùng. |
| `CHAT_ROOM_TURN_CREDENTIAL` | Rỗng; mật khẩu TURN nếu dùng. |

Cần HTTPS ngoài localhost. Mạng NAT/firewall hạn chế cần TURN; STUN mặc định không bảo đảm kết nối
mọi mạng. TURN chỉ chuyển tiếp media, không ghi tệp. P2P tăng upload của người trình chiếu theo số
người xem; chưa kiểm thử tải nhiều người. Video cục bộ cần trình duyệt hỗ trợ captureStream;
Chrome đã kiểm tra, trình duyệt thiếu hỗ trợ dùng chia sẻ màn hình nếu có. Âm thanh chia sẻ màn hình
phụ thuộc trình duyệt/nguồn người dùng chọn. Video YouTube phải cho phép nhúng.

Kiểm tra bổ sung ngày 17-11: Maven verify bốn lớp ở trên qua 32 test, gồm quyền trình chiếu,
signaling theo phiên, ID sai và callback disconnect muộn. Chrome hai tài khoản xác nhận ảnh P2P
bằng pixel giải mã, video WebM có track âm thanh, tạm dừng/tiếp tục, YouTube phát thực tế cùng
pause/seek, fullscreen và dọn nguồn khi đứng dậy/rời phòng.
Bố cục Caro/cờ vua/cờ tướng được kiểm tra ở 1280×840, 375×780 và 844×390: toàn bàn,
đồng hồ/lượt và thao tác chính nằm trong khung; lịch sử có vùng cuộn riêng, Caro vẫn kéo bàn 50×50.
Người xem vào muộn nhận ảnh đang phát, kể cả trên viewport điện thoại.
FE qua 48 test phòng/guard, 3 test hợp đồng phòng và 16 test menu/section card; lint/typecheck qua.
Production/staging build và bundle guard qua. Cỡ quân co theo ô để không chồng chữ ở màn hình ngang thấp.

Nghiệm thu cục bộ ngày 17-11-2026: 28 test ở bốn lớp trên thành công và đóng gói Maven thành công.
Chrome desktop dùng ba tài khoản/phiên để chơi xong mỗi loại cờ, xem ván, thử gửi nước từ người xem,
đồng bộ reaction/chat và chạy sàn màu. Chrome cảm ứng 375px kiểm tra cả hai loại cờ, emoji ghép,
cận cảnh và không tràn trang. Hồi quy kiểm tra nhảy/vẫy/đập tay, Caro rời rồi vào lại,
đếm hết 120 giây và kết toán một lần, Trên/Dưới chung kết quả/lịch sử, bàn phím Enter cho reaction.
Map được kiểm tra đường tới cả 12 ghế mới. Các phiên trình duyệt dùng backend kiểm thử cô lập và
PostgreSQL kiểm thử, với identity/JWT fixture; không thay thế kiểm thử đăng nhập mật khẩu trên môi trường thật.
`mvn clean verify` toàn repo chưa xanh trên máy này do Docker/Testcontainers không khả dụng
(lỗi khởi tạo các test tích hợp ngoài phạm vi phòng). Không dùng DB phát triển dùng chung để thay thế.
FE kiểm tra lại trên bundle production thành công ở desktop và mobile sau khi chuyển trạng thái
chọn quân/phong cấp vào store. 45 test phòng chat và guard giao diện qua; contracts 341 test và UI
564 test qua. Format, lint, typecheck, production/staging build và bundle guard qua.
Kiểm tra FE toàn repo chưa xanh: 9 test Postman/mock về nhóm/người dùng thất bại; worker của lượt
toàn LMS dừng bất ngờ. Lượt chia nhỏ xác nhận thêm 7 test thất bại ở route table, migration registry
và asset library ngoài phạm vi thay đổi; 635 test trong lượt đó qua. Không sửa các module này trong task phòng chat.

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
