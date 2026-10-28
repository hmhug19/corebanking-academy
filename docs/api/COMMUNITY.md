# Community — contract toàn bộ phạm vi và trạng thái triển khai

Trạng thái source ngày 28/10/2026: **đã triển khai C01–C138 và mapping 51 bảng**.
**Đang hoạt động: 135 API. Tạm dừng C126–C128** bằng comment `@RestController` và import tương ứng trong
`CommunityAiController`: 3 route `/api/v1/community/ai/**` không đăng ký, không xuất hiện trên Swagger và trả 404.
Giữ service/DTO/bảng AI để bật lại sau, không xóa dữ liệu hoặc đổi schema. Contract AI bên dưới là đặc tả lưu lại,
chưa sử dụng ở thời điểm này. Tìm kiếm thường, index, job và Assistant hiện có không thay đổi.
Thiết kế và cột tại [COMMUNITY_DATABASE.md](COMMUNITY_DATABASE.md). Envelope/HTTP/validation theo
[API_CONTRACT.md](API_CONTRACT.md). Cách gọi các route đã có: [COMMUNITY_CURL.md](COMMUNITY_CURL.md).

## Trạng thái source hiện tại

| Nhóm | Đã có source | Ghi chú vận hành/kiểm chứng |
|---|---|---|
| Nội dung/Q&A/Blog | Draft/revision, publish/history, accept, vote SET, tags/merge, ACL, lifecycle/comment, maintainer, duplicate | Kiểm tra version khi ghi; bản published bất biến |
| Guild/Wiki/Radar | Membership/invite/owner/feed/pin, cây Wiki, phê duyệt đúng revision, Tech Radar, maintenance | Quyền moderator không tự mở nội dung Guild private |
| Tri thức cộng đồng | Help/expertise, lessons learned, knowledge request/claim/fulfill, review/invite/thread/conclude/follow-up | Kiểm quyền nguồn và đích, anchor gắn revision |
| Cá nhân/điều phối | Preferences/onboarding, collections/bookmarks, subscriptions/notifications/digests, reports/evidence/actions | Thông báo và collection kiểm lại ACL lúc đọc |
| Liên module | Feed/search, links, Learning resource pins, AI/source/feedback và Assistant read tool | Adapter công khai giữ policy module sở hữu; không mở quyền qua ID |
| Media và worker | Multipart/reservation/manifest/retry/abort, GET/HEAD/Range, probe/cleanup, transcript; outbox/index/fanout/digest/ledger/badge | Worker mặc định tắt; phải cấu hình actor kỹ thuật và hạ tầng bên dưới |
| Persistence | Baseline SQL/mapping 51 bảng, scalar ID, audit/version, không FK/UNIQUE nghiệp vụ | Chưa chạy DDL hoặc seed trên server; DB hiện hữu cần migration được review riêng |

Schema thực tế để FE import: [community-openapi.json](../postman/community-openapi.json); Swagger kiểm tra đủ 135 operation đang bật,
response thành công 200 và schema JSON/binary phù hợp. Có route không có nghĩa mọi tích hợp ngoài đã được nghiệm thu.
Kiểm chứng mới nhất dùng PostgreSQL 16.15 với **pgvector 0.8.0 thật**, DDL gốc không thay kiểu cột:
`clean verify` **235 test pass, 0 failure/error, 0 skipped**; đã chạy cả 49 test từng bị bỏ qua vì thiếu Docker.
Các kiểm tra HTTP riêng dùng S3 server SeaweedFS 4.51 và FFmpeg/ffprobe thật, gồm multipart/Range,
transcript, scheduler, digest, ledger reversal, cleanup và phục hồi sau crash.
Đây là hạ tầng local tách biệt; chưa kiểm chứng cấu hình AIStor/TLS/network hay tải thực tế của production.
Không chạy sinh câu trả lời bằng model Ollama thật vì API Community AI đang tạm dừng; đã kiểm retry khi model không có.
Assistant có tool `search_community` chỉ tìm và trả liên kết do server dựng; C126 là luồng trả lời AI có citations.
Tool kiểm lại toàn bộ nguồn trước kết thúc lượt, loại toàn bộ kết quả nếu quyền hoặc revision thay đổi.

Kiểm thử integration ở `CommunityApiIT` phải bật `-Dcommunity.api.integration=true`, dùng DB tổng hợp riêng;
xem lệnh và giới hạn tại [cURL/kiểm chứng](COMMUNITY_CURL.md#7-kiểm-chứng-tự-động).

### Kết quả kiểm thử tích hợp ngày 28/10/2026

| Phần | Kết quả đã chạy |
|---|---|
| Schema | Chạy nguyên bản Identity → Learning → media/progress → Knowledge → Community trên PostgreSQL/pgvector thật; Hibernate `validate` pass. Catalog/demo/verify nguyên bản pass, 51 bảng/670 cột và 142 tham chiếu logic hợp lệ. |
| Hồi quy | 235 test pass, 0 skipped; gồm 35 test Community, vector top-K lọc ACL trước limit và resolve từ chối ngay sau thu hồi quyền. Test Assistant cập nhật danh sách tool nhưng giữ nguyên kiểm tra màn hình theo role. |
| Media HTTP thật | Video MP4 21.277.280 byte, 3 part; retry initiate/part không nhân byte. Worker kiểm SHA-256/probe/faststart rồi ready. GET/HEAD/Range 206, invalid/multi-range 416, stale If-Range trả toàn bộ file, thu hồi ACL chặn đọc. |
| Transcript | Tạo/sửa segment nguyên và thập phân trả version khớp DB đã commit; publish và đọc transcript thực tế pass. |
| Worker | Scheduler thực giao notification, vote SET không nhân credit, đổi ACL tạo reversal và lọc notification cũ; digest UTC được giao/read; cleanup xóa object mồ côi và giữ file còn revision tham chiếu. |
| Crash | Dừng đột ngột đúng JVM test khi asset có lease processing/lần 1. Restart nhận lease mới, cùng asset ready/lần 2, hết lease cũ, có playback và duration 5 giây. |
| Model không sẵn có | Index retry tối đa 3 lần, không chặn fanout/ledger/digest. Đây là kiểm tra nhánh lỗi, không phải chứng minh inference Ollama thành công. |

Hai lỗi phát hiện và đã sửa trong đợt kiểm tra này:

- Complete multipart từng phụ thuộc XML `ListParts.Initiator` mà S3 provider có thể không trả. Nay dùng part number/ETag
  đã ACK để complete, provider kiểm ETag; worker vẫn kiểm tổng byte và SHA-256 trước ready.
- JSON segment tạo bằng `valueToTree(BigDecimal)` khác kiểu node với snapshot Hibernate, làm tăng version sau response.
  Chuẩn hóa segment qua JSON khi lưu; kiểm thử create/update/publish bảo đảm version trả về đúng DB đã commit.

pgvector local được build từ source chính thức bằng compiler portable, tắt SIMD dispatch cho toolchain test;
không cài vào PostgreSQL hệ thống. Kết quả này kiểm chứng SQL/logic, không phải benchmark extension production.
Các timer retry/retention được rút ngắn riêng trong fixture; production giữ mặc định đã mô tả bên dưới.
Chưa có môi trường staging do người vận hành cung cấp; cần smoke test trên cấu hình triển khai thực trước khi mở traffic.

## Phạm vi đầy đủ và quy ước

### Cài đặt và vận hành

1. Sao lưu DB và kiểm tra baseline đã chạy. Với DB mới, chạy thứ tự trong
   [SQL README](../sql/README.md), giữ `ddl-auto=validate`. Không chạy lại baseline trên DB đã có bảng.
   Baseline Community không seed tài khoản, moderator, nội dung hoặc mật khẩu.
2. Cấu hình S3/AIStor dùng chung tại [hướng dẫn triển khai](../operations/DEPLOY_UBUNTU.md#aistor-integration).
   Bucket phải tồn tại và credential chỉ cấp quyền bucket được cấu hình. File lâu dài không fallback local.
   Cài `ffprobe`/`ffmpeg` trong runtime để kiểm video; API upload chưa đồng nghĩa file đã `ready`.
3. Cấu hình worker bằng các property bên dưới. `worker-user-id` là tài khoản kỹ thuật active đã được quản trị cấp;
   ứng dụng không tự tạo hoặc nâng quyền tài khoản. Các job do moderator yêu cầu kiểm lại quyền người tạo khi chạy.
4. Cấu hình Ollama theo tài liệu Knowledge hiện có, embedding `bge-m3` đúng 1024 chiều và extension pgvector.
   Không tự tải model. Nếu Ollama lỗi, outbox nghiệp vụ vẫn fanout/reconcile; index retry độc lập có giới hạn.
5. Kiểm thử trên staging: multipart → probe → ready → publish → GET/HEAD/Range; index → retrieval → citation;
   bật worker và xác minh lease/retry/digest qua restart trước khi bật trên production.

```yaml
app:
  community:
    jobs:
      enabled: true
      worker-user-id: ${COMMUNITY_WORKER_USER_ID}
      poll-ms: 3000
      initial-delay: 15s
      lease: 300s
      heartbeat: 60s
      retry-delay: 30s
      second-retry-delay: 120s
    media:
      worker-enabled: true
      cleanup-enabled: true
      worker-user-id: ${COMMUNITY_WORKER_USER_ID}
      poll-ms: 3000
      initial-delay: 15s
      lease: 300s
      heartbeat: 60s
      retry-delay: 30s
      second-retry-delay: 120s
      orphan-retention: 72h
      upload-ttl: 24h
      probe-timeout: 120s
      reservation-grace: 30s
      ffprobe: ffprobe
      ffmpeg: ffmpeg
```

Mặc định `jobs.enabled`, `media.worker-enabled`, `media.cleanup-enabled` đều false; các giá trị thời gian trên
là mặc định trong `CommunityJobsProperties`/`CommunityProperties`. Không bật khi thiếu actor/hạ tầng.
Heartbeat phải nhỏ hơn lease; duration phải dương. Worker claim bằng lease token trong transaction ngắn,
IO ngoài transaction; mất lease không được ACK kết quả. Retry tự động tối đa 3 lần với backoff 30s/120s.
Upload part size 8 MiB, upload TTL 24h; API trả manifest để FE resume đúng part/checksum.

Theo dõi C138 và C130/C132 theo quyền moderator, trạng thái asset/upload/digest và log mã lỗi (không log
prompt/answer/token). C46 complete lại để retry lỗi hạ tầng, C137 retry digest và job reconcile/reindex là thao tác tường minh;
retry không tự sửa dữ liệu nguồn hoặc cấp thêm quyền. Event `index_refresh` lỗi không đảo lại nội dung đã publish.
Cleanup chỉ xóa asset không còn bất kỳ revision reference, hết retention và giữ cleanup lease; không xóa file đang dùng.
Nếu cần rollback ứng dụng, tắt hai worker và cleanup, dừng ghi rồi dùng phương án backup/migration đã duyệt;
không DROP schema hoặc xóa metadata để chạy lại baseline.

### Trình tự tích hợp FE

- Tạo draft → lưu nội dung → gắn asset `ready` → publish; Wiki/Radar đi qua submit/decision thay vì publish trực tiếp.
  Sau mỗi lệnh ghi lấy lại version từ response. Danh sách chỉ hiển thị nguồn còn ACL; tổng số không bao gồm nguồn private.
- Review: publish proposal → mời reviewer → reviewer accept → tạo thread gắn đúng revision/asset → giải quyết thread
  → conclude. Muốn sửa sau kết luận phải tạo follow-up; thread cũ giữ anchor của revision cũ.
- Recording: upload primary video → chờ ready → tạo recording có đúng một `primary_video`; transcript là revision
  riêng, segment nằm trong duration, không chồng nhau và body khớp segment; publish transcript để đưa vào index.
- Learning: lưu draft khóa học trước, lấy `courseRevisionId`/`lessonId` mới rồi PUT C124 để gắn tài nguyên.
  C124 là SET toàn danh sách và pin đúng `contentRevisionId`, không chạy theo latest. Lưu lại toàn bộ draft Learning
  có thể tạo lesson ID mới: cần cập nhật C124 trước publish. Clone khóa học copy pin và remap lesson của bản clone.
- AI C126 dùng `requestId` mới cho câu hỏi mới; retry giữ ID/payload. Theo `status` processing/completed/no_sources/failed/discarded,
  không suy thành công từ HTTP 200. Kết quả chỉ giữ RAM 10 phút, tối đa 100 lượt; metadata không lưu prompt/answer.
  Sau restart hoặc hết cache, `resultRetained=false`; FE phải tạo lượt mới nếu muốn hỏi lại.
- `capabilities` chỉ hỗ trợ UI; server luôn kiểm lại quyền. OpenAPI là schema chính xác: phần DTO dùng map boolean
  hoặc danh sách tên thao tác cho media/transcript/review/resource; FE không dùng chung một kiểu cho hai dạng này.


**Tất cả chức năng đã bàn đều nằm trong phạm vi triển khai**, bao phủ toàn bộ thay vì chỉ một đợt triển khai. Bao gồm Q&A, Blog, video sharing, Guild, Wiki/Radar, review kiến trúc, bài học sự cố, yêu cầu tài liệu, search toàn app, digest/onboarding/mentions, collections chia sẻ, danh bạ, moderation, độ mới, thống kê, điểm/huy hiệu, liên kết Learning và AI có nguồn. Thứ tự xây dựng theo phụ thuộc kỹ thuật không loại chức năng khỏi contract.

Dùng 9 kind của DB; visibility organization/guild/restricted; grant user/Guild. Guild thuộc một org và thành viên cùng org. Không tạo role/account Identity hoặc tự cấp quyền để chạy Community. Search và Learning/Knowledge/Assistant qua public adapter, giữ nguyên policy của từng module.

- Prefix `/api/v1/community`; caller từ SecurityContextUtils, policy từ dữ liệu thực. JSON trả HTTP 200 + BaseResponse<T>, kể cả validation/xung đột; Swagger khai báo 200/schema và lỗi 400/401/403/500. File binary 200/206, lỗi theo contract hiện có; HEAD cùng HTTP/header tương ứng GET, không có body.
- List dùng PageResponse(items,page,size,totalElements,totalPages); page từ 0, size mặc định 20/tối đa 100. Sort allowlist và ID tie-breaker. Lọc ACL trước count/pagination/snippet. ID bảng mới dương; userId là chuỗi tối đa 20 giữ số 0 đầu; timestamp ISO-8601 UTC.
- Lưu draft/publish cần expectedContentVersion và expectedRevisionVersion. ACL/tag/lifecycle/accept cần expectedContentVersion. Sửa comment/tag/report dùng expectedVersion của resource tương ứng. Backend trả version mới; FE không auto retry bằng version mới để ghi đè người khác.
- Vote/bookmark/subscription/read-notification là SET trạng thái của caller dưới khóa grain, không cộng dồn. Cùng trạng thái trả thành công không tạo thêm event. Hai trạng thái khác nhau đồng thời tuân thứ tự commit; FE bỏ response của thao tác cũ.
- Tạo question/article/answer/comment có clientRequestId UUID; lưu key + request_hash SHA-256 canonical payload gồm kind/parent. Cùng actor/key/payload trả resource cũ, khác payload lỗi COMMUNITY-IDEMPOTENCY_CONFLICT. Kiểm quyền hiện tại trước trả lại; key của người khác không lộ resource. Giữ key khi xóa mềm, không tạo lại bằng key cũ.
- Mỗi content tối đa một draft; tạo/lấy draft trả draft hiện tại. Submitted/rejected không bị ghi đè để sửa; tạo revision_no mới. Payload body/context/assets bất biến khi submitted/published/superseded; trạng thái/audit có thể đổi theo luồng.
- Mọi ghi quan hệ không FK/UNIQUE phải validate dưới khóa grain hoặc khóa ghi chung hiện có ở bản đầu. Audit/outbox cùng transaction; S3/probe ngoài transaction dài. Chưa tự thêm UNIQUE/FK vật lý hoặc chạy SQL server.

## Ma trận quyền

R = người đọc có ACL; A = tác giả; M = maintainer; E = editor được grant; GM = Guild moderator; OM = org moderator. Mọi người phải active, org hợp lệ, không còn yêu cầu đổi mật khẩu. M mặc định là A khi tạo; có thể chuyển người duy trì. E do grant user/Guild; GM do membership Guild.

| Thao tác | R | A | M | E | GM | OM |
|---|---|---|---|---|---|---|
| Đọc published | Có ACL | Có ACL | Có ACL | Có ACL | Có ACL trong Guild | Có ACL hoặc case được giao |
| Đọc draft/history chưa publish | Không | Có | Có | Có | Không tự động | Case được giao hoặc quyền duyệt |
| Tạo bài/câu hỏi của mình | Có | Có | Có | Có | Có | Có |
| Tạo/sửa draft | Không | Có | Có | Có | Không tự động | Không sửa thay tác giả |
| Publish Q&A/Blog | Không | Có | Có | Không | Không tự động | Không publish thay tác giả |
| Viết answer/comment | Parent còn cho tương tác | Như R | Như R | Như R | Như R | Như R |
| Sửa answer/comment người khác | Không | Không | Không | Không | Không | Không |
| Vote | Trừ nội dung của mình | Không tự vote | Như R | Như R | Như R | Như R |
| Accept/unaccept | Không | Chỉ A của question | Không, trừ cũng là A | Không | Không | Không |
| Đổi ACL/phạm vi xem | Không | Có | Không | Không | Không | Không tự mở quyền |
| Close/reopen/archive/xóa mềm | Không | Theo vòng đời | Theo vòng đời | Không | Case Guild | Case org |
| Hide/restore vi phạm | Không | Không | Không | Không | Case Guild | Case org |
| Quản lý tag/alias | Không | Không | Không | Không | Đề xuất | Có trong org |
| Duyệt chuẩn Wiki/Radar | Không | Không tự duyệt | Không tự duyệt | Không tự duyệt | Rà soát | Có thẩm quyền, không đóng góp revision |
| Cấp org moderator | Không | Không | Không | Không | Không | ADMIN đúng scope/SUPER_ADMIN, không tự cấp |

Moderator ẩn/đóng/khôi phục có lý do, không sửa body người khác. SUPER_ADMIN giữ policy quản trị gốc, nhưng đọc nội dung riêng phải qua đường điều phối/audit rõ ràng, không tự trở thành tác giả/approver hoặc bypass cấm tự duyệt. Guild role không cấp quyền quản trị Identity.

### Mất quyền và chống lộ dữ liệu

1. Organization chỉ cùng owner_org_id hiện tại; restricted gồm A/M và grant reader/editor active chưa hết hạn; Guild cần membership active của primary_guild_id. Author của answer phải qua question ACL trước; comment/thread/file kế thừa nội dung cha.
2. Grant reader không đọc draft; editor sửa draft nhưng không publish/đổi ACL. Mời hỗ trợ/review, feed Guild, collection và link không mở ACL. Cross-org grant bị từ chối. Tag count chỉ đếm bài caller được đọc.
3. Rời Guild, đổi org, revoke/expire grant hoặc thu hẹp visibility có hiệu lực ở request kế tiếp cho cả revision cũ, Range file, bookmark, notification/search. Không lộ title, snippet, ghi chú hoặc count trái quyền. Không đưa private content vào kho Knowledge toàn app.
4. Tác giả chuyển org mất quyền tại org cũ; bài published giữ để tra cứu, không tự chuyển owner_org. OM org cũ chỉ định maintainer cùng org qua case/audit; giữ credit tác giả. Maintainer không được accept thay A; accept hợp lệ hiện có được giữ. User bị khóa không làm xóa bài/vote/lịch sử, nhưng không được đọc/ghi mới.
5. Guild archived chỉ đọc nội dung còn ACL, không đăng/sửa mới; org ngừng hoạt động không đọc qua API thường. Nội dung hidden/xóa mềm không trả payload/history cho reader kể cả tác giả; A/M chỉ nhận tombstone/lý do an toàn.
6. Evidence riêng tư chỉ OM đúng scope được assign report xem, ghi audit truy cập. List report chưa assign chỉ metadata tối thiểu, không excerpt. GM chỉ case Guild; không tự đọc toàn org.
7. Asset đã attach chỉ mở qua revisionAssetId, kiểm revision và parent ACL mỗi request. Uploader chỉ mở tệp chưa attach của mình qua luồng upload; không dùng assetId trần để vượt quyền. Toàn bộ stream qua BE/JWT, không cấp signed URL dài hạn; playback Community có ACL qua BE, chưa cần đổi hạ tầng proxy để đủ phạm vi.

## Chuyển trạng thái đã chốt

| Đối tượng | Chuyển trạng thái, điều kiện và tác động |
|---|---|
| Q&A/Blog revision | draft → published do A/M; validate body/tag/assets. Publish bản mới làm bản cũ superseded, giữ payload cũ. Không sửa bản published tại chỗ. |
| Wiki/Radar revision (phạm vi Guild/Wiki) | draft → submitted do A/M; submitted → published/rejected do OM không đóng góp revision. Changes_requested chuyển bản submitted thành rejected; tạo draft mới để sửa, không quay ngược revision cũ thành draft. |
| Question active → closed | A/M hoặc moderator có case; ngừng answer/comment/vote/sửa mới; còn accept/unaccept answer đã có và report. Article không dùng closed. |
| closed → active | A/M nếu tự đóng; nếu moderator đóng thì chỉ moderator có case được mở lại. Không vượt hidden/deleted/archived. |
| active/closed → archived | A/M hoặc moderator; chỉ đọc, không accept/tương tác/sửa mới. Unarchive phục hồi trạng thái trước archive từ audit, không tự mở question từng bị moderator đóng. |
| Nội dung → hidden | Chỉ moderator được giao case, có reason; lưu snapshot trạng thái trước hide. Restore chỉ dùng action gần nhất và version khớp; không phục hồi ACL cũ hoặc làm sống lại nội dung còn deleted_at. |
| Xóa mềm | A/M nếu còn quyền và chưa hidden, hoặc moderator có case. Question có answer published của người khác thì A/M không tự xóa, dùng close/report. Answer/article/comment của mình được xóa mềm. Không hard delete/cascade. |
| Khôi phục xóa mềm | Moderator được giao report yêu cầu khôi phục, kiểm version/ACL hiện tại; không tự khôi phục accept hoặc mở lại câu hỏi. |
| Question resolution | unanswered: không answer published còn hiển thị; answered: có answer nhưng chưa accept; solved: accept hợp lệ. Draft không làm tăng count. Duplicate là link + closed_reason, không thay resolution_status. |
| Accept | Chỉ A question còn quyền, active/closed; gửi answerContentId + answerRevisionId là bản published hiện tại của đúng answer/question. Cả hai null nghĩa unaccept. |
| Answer có bản mới sau accept | Giữ ID answer và revision đã xác nhận; DTO báo acceptedRevisionIsCurrent=false. A có thể xác nhận lại bản mới. |
| Answer/revision accepted bị ẩn/xóa | Bỏ cả bốn trường accept, tính lại resolution cùng transaction. Restore không tự accept lại. Hide question giữ accept lịch sử nhưng không lộ payload. |
| Comment | visible → hidden qua moderation; A chỉ sửa/xóa của mình khi parent còn tương tác. Parent cùng content/revision, không cycle, tối đa ba cấp. Xóa parent giữ tombstone/reply còn quyền. |
| Report | open → in_review khi được assign; in_review → resolved/dismissed qua action+reason. Một report có nhiều moderation_actions append-only, không sửa status tùy ý. |
| Guild (phạm vi Guild/Wiki) | open join kích hoạt ngay; approval pending → active/rejected; invite cần người nhận đồng ý. left quay lại theo cùng policy trên cùng membership. Chuyển owner nguyên tử; owner cuối không tự rời. |
| Architecture Review (phạm vi review) | draft → open có published revision; open → in_review khi reviewer nhận; in_review → concluded do A/M, concludedRevisionId cụ thể + kết luận và không còn thread open trên bản chốt. open/in_review → closed để dừng, cần lý do. Đánh giá lại sau concluded tạo proposal/vòng review mới, không ghi đè kết luận cũ. |

Các thao tác accept/publish/moderation phải khóa content/parent cần thiết, kiểm version, audit/outbox nguyên tử. Quyết định duyệt trỏ đúng revision bất biến; người từng đóng góp revision không tự duyệt, kiểm tác giả/audit chỉnh sửa chứ không chỉ updated_by cuối.

## API nền C01–C49

Các route dưới đây là contract đích; đối chiếu bảng trạng thái source ở đầu tài liệu trước khi gọi.
Đường dẫn sau `/api/v1/community`. C = ContentDetail; R = RevisionDetail; Q = QuestionDetail; P = PageResponse.

| ID | Method / đường dẫn | Request hoặc query | data thành công |
|---|---|---|---|
| C01 | GET `/questions` | q,tagIds,resolutionStatus,page,size,sort | `P<QuestionCard>` |
| C02 | GET `/questions/suggestions` | q 5..300,limit 1..10 | QuestionCard[] còn ACL |
| C03 | POST `/questions` | CreateContent(question) | Q draft |
| C04 | GET `/questions/{questionId}` | includeOwnDraft=false | Q |
| C05 | GET `/questions/{questionId}/answers` | page/size; sort accept,score,time,id | `P<AnswerCard>` |
| C06 | POST `/questions/{questionId}/answers` | CreateContent(answer); parent active | C draft |
| C07 | PUT `/questions/{questionId}/accepted-answer` | AcceptRequest | Q/version mới |
| C08 | GET `/articles` | q,tagIds,page,size,sort | `P<ContentCard>` |
| C09 | POST `/articles` | CreateContent(article) | C draft |
| C10 | GET `/contents/{contentId}` | includeOwnDraft=false | C |
| C11 | GET `/me/contents` | kind,publicationState,lifecycleStatus,page,size | `P<ContentCard>` của caller còn ACL |
| C12 | POST `/contents/{contentId}/draft` | expectedContentVersion | R draft hiện tại/clone mới |
| C13 | PUT `/contents/{contentId}/draft` | SaveDraft | R + contentVersion |
| C14 | POST `/contents/{contentId}/publish` | PublishRequest | C |
| C15 | GET `/contents/{contentId}/revisions` | page,size; revisionNo desc | `P<RevisionCard>` còn quyền |
| C16 | GET `/contents/{contentId}/revisions/{revisionId}` | Không | R |
| C17 | POST `/contents/{contentId}/lifecycle` | LifecycleRequest | C/tombstone |
| C18 | PUT `/contents/{contentId}/access` | AccessRequest thay toàn bộ grant user | AccessView + contentVersion |
| C19 | PUT `/contents/{contentId}/tags` | expectedContentVersion,tagIds | tags + contentVersion |
| C20 | PUT `/contents/{contentId}/vote` | value=-1/0/1 | score,upvotes,downvotes,myVote |
| C21 | GET `/tags` | q,page,size; name/slug,id | `P<TagView>` cùng org |
| C22 | POST `/tags` | slug,name,description,aliases; OM | TagView |
| C23 | PUT `/tags/{tagId}` | expectedVersion,name,description,status,aliases; OM | TagView |
| C24 | POST `/tags/{tagId}/merge` | expectedVersion,targetTagId,reason; OM | TagView |
| C25 | GET `/contents/{contentId}/comments` | page,size; createTime,id | `P<CommentView>` |
| C26 | POST `/contents/{contentId}/comments` | clientRequestId,parentCommentId?,reviewThreadId?,bodyMarkdown | CommentView |
| C27 | PUT `/comments/{commentId}` | expectedVersion,bodyMarkdown | CommentView của caller |
| C28 | DELETE `/comments/{commentId}` | expectedVersion query | null; xóa mềm |
| C29 | GET `/me/bookmarks` | page,size | `P<ContentCard>` còn quyền |
| C30 | PUT `/me/bookmarks/{contentId}` | saved boolean | contentId,saved |
| C31 | GET `/me/subscriptions` | page,size | `P<SubscriptionView>` còn quyền |
| C32 | PUT `/me/subscriptions` | một contentId/tagId/guildId/authorUserId; mode instant/digest/muted/off | SubscriptionView/null khi off |
| C33 | GET `/me/notifications` | unreadOnly,page,size | `P<NotificationView>` còn quyền |
| C34 | POST `/me/notifications/{notificationId}/read` | Không | NotificationView; repeat an toàn |
| C35 | POST `/questions/{questionId}/help-requests` | đúng một targetUserId/targetGuildId,dueAt? | HelpRequestView |
| C36 | PUT `/help-requests/{helpRequestId}` | expectedVersion,action accept/decline/resolve/cancel | HelpRequestView |
| C37 | POST `/reports` | một contentId/commentId,reasonCode,description? | ReportView |
| C38 | GET `/moderation/reports` | status,assignedToMe,page,size | `P<ReportCard>` tối thiểu trong scope |
| C39 | POST `/moderation/reports/{reportId}/assign` | expectedVersion,moderatorUserId | ReportView |
| C40 | GET `/moderation/reports/{reportId}/evidence` | Không | EvidenceView; chỉ OM được assign, audit |
| C41 | POST `/moderation/reports/{reportId}/actions` | ModerationActionRequest | ReportView |
| C42 | PUT `/moderation/moderators/{userId}` | orgId,active,reason,expectedVersion; ADMIN đúng scope/SUPER_ADMIN | ModeratorView |
| C43 | POST `/assets/uploads` | UploadInit | UploadView |
| C44 | GET `/assets/uploads/{uploadId}` | Không | UploadView + manifest; uploader |
| C45 | PUT `/assets/uploads/{uploadId}/parts/{partNumber}` | octet-stream + X-Part-SHA256 | PartAck trong envelope |
| C46 | POST `/assets/uploads/{uploadId}/complete` | Không | UploadView; chưa đồng nghĩa ready |
| C47 | DELETE `/assets/uploads/{uploadId}` | Không | UploadView; abort uploading |
| C48 | GET `/revision-assets/{revisionAssetId}/content` | Range/If-Range tùy chọn | binary 200/206 |
| C49 | HEAD `/revision-assets/{revisionAssetId}/content` | Range/If-Range tùy chọn | Header GET, không body |

includeOwnDraft không cấp quyền; reader history chỉ thấy bản từng published/superseded còn được đọc. QuestionCard không nhúng toàn bộ answer/comment, dùng C05/C25 phân trang. Suggestions không bắt buộc có hit trước khi tạo câu hỏi.

Luồng FE chuẩn: C02 tìm trước → C03 lưu question draft → C14 publish; người trả lời dùng C06 tạo answer draft → C14 publish answer → A question dùng C07 accept. Blog dùng C09 → C13 nếu cần sửa → C14. FE không coi draft là câu trả lời đã xuất bản hoặc tự tăng answerCount khi C06 thành công.

C17 action close/reopen/archive/unarchive/soft_delete; riêng architecture_review có open_review khi revision đã published. Review close/conclude dùng C85/C84, không gọi close của question để vượt workflow. C12 trả draft đã có sau kiểm ACL; chỉ tạo draft mới thì kiểm expectedContentVersion, để retry không tạo hai bản. Moderator hide/restore/restore_deleted/assign_maintainer chỉ qua C41 có report/reason. Lifecycle và tác nhân đóng/ẩn được audit; không tự khôi phục ACL cũ. Tombstone không chứa body/file.

C20 chỉ áp dụng question/answer/article/recording/lesson_learned, không dùng vote thay phê duyệt Wiki/Radar/review.

C22 trùng slug/alias trả COMMUNITY-DUPLICATE để chọn tag cũ. C23 chỉ active/archived, status=merged chỉ qua C24. Merge di chuyển content_tags/subscriptions/expertise, gộp grain và alias dưới cùng khóa, chặn cycle/cross-org. Không lộ usage count trái quyền.

Bookmark C29/C30 dùng collection private mặc định tên `Đã lưu` mỗi owner, tạo lười dưới khóa (owner,name). Collection mặc định không được đổi tên/xóa/chia sẻ; collection khác dùng C102–C107 và ACL riêng. saved=false xóa item, repeat được.

C35 cùng question/target đang pending/accepted trả yêu cầu cũ, không spam; target user hoặc thành viên active của target Guild có quyền đọc question accept/decline, ghi responded_by; requester cancel, A question resolve qua C36. C37 cùng reporter/target/reason còn open/in_review trả report cũ; description lần đầu giữ nguyên. C39 chỉ người quản lý điều phối trong scope assign moderator hợp lệ. C42 dùng version của grant, chưa có thì expectedVersion=0 và kiểm trùng dưới khóa; SET cùng trạng thái trả hiện tại trước kiểm version để retry an toàn. C42 không tự cấp quyền cho chính mình, thu hồi không làm mất lịch sử case.

## DTO và validation

| DTO | Field và quy tắc |
|---|---|
| CreateContent | clientRequestId UUID; title<=255, bodyMarkdown<=100.000 không null (draft cho rỗng), summary<=2.000; visibility organization/guild/restricted; tagIds khác nhau tối đa 10; context allowlist; assets tối đa 20 với assetId,usage,caption<=5.000,altText<=255,sortNo>=0. Answer bỏ visibility/tagIds và kế thừa question. Không nhận actor/org/kind tùy ý. |
| SaveDraft | expectedContentVersion>=0,expectedRevisionVersion>=0; replace title/bodyMarkdown/summary/context/assets. Tag/ACL qua endpoint riêng, không ghi trong draft request. |
| PublishRequest | Hai expectedVersion như trên; title 5..255,body 20..100.000; question có 1..10 tag active; asset ready chưa xóa mềm, cùng org và có quyền công bố nguồn. Cấm raw HTML/script/iframe/external embed ngoài code block; nội dung code block phải escape và chỉ hiển thị như text, renderer sanitize Markdown. |
| AcceptRequest | expectedContentVersion>=0; answerContentId/answerRevisionId cùng có hoặc cùng null (bỏ accept), không dùng sentinel 0. |
| LifecycleRequest | expectedContentVersion,action đúng C17,reason 5..3.000; không nhận status tùy ý. |
| AccessRequest | expectedContentVersion,visibility,primaryGuildId bắt buộc với guild; grants=[{userId?,guildId?,permission:reader/editor,expiresAt?}], đúng một user/Guild mỗi grant, tối đa 100 chủ thể khác nhau active cùng org. Organization/guild yêu cầu grants=[]; A/M có quyền nền, không tạo grant giả cho họ. |
| ModerationActionRequest | expectedReportVersion,expectedTargetVersion,action hide/restore/restore_deleted/soft_delete/close/assign_maintainer/warn/dismiss,reason 5..3.000; maintainerUserId chỉ cho assign_maintainer; restoreActionId bắt buộc khi restore để khôi phục đúng snapshot gần nhất. |
| UploadInit | requestId UUID,originalName<=255,mimeType<=100,sizeBytes>0,checksumSha256 đúng 64 hex; ownerOrg do actor, không nhận org tự khai. |

q<=300, tagIds<=10; resolutionStatus=unanswered/answered/solved; question sort mặc định lastActivityAt desc,id desc, có score/createTime; article sort publishedAt desc,id desc. Comment 1..10.000 ký tự, tối đa 3 cấp. Tag aliases<=20, mỗi alias<=100, description<=3.000. Enum/sort sai trả validation, không bỏ filter. Context chung nhận technologies=[{name<=100,version<=100}],environment<=255,limitations<=5.000; tối đa 20 technology và chỉ nhận schema đúng kind, không nhận metadata Wiki/Radar qua kind khác. Schema các kind chuyên biệt tại phần mở rộng.

Create question (C03), ID/tag là minh họa phải lấy từ dữ liệu thực:

```json
{"clientRequestId":"8c5fe9be-d989-4fa6-bac5-2e1d78b0880b","title":"Oracle UCP báo timeout khi lấy connection","bodyMarkdown":"## Bối cảnh\nỨng dụng thử nghiệm gặp timeout khi lấy connection.\n## Đã thử\nKiểm tra cấu hình pool và kết nối database.","summary":"Cần tìm nguyên nhân timeout connection pool.","visibility":"organization","tagIds":[11,12],"context":{"technologies":[{"name":"Java","version":"17"}],"environment":"dev","limitations":"Dữ liệu mô phỏng, không đính kèm thông tin kết nối thật."},"assets":[]}
```

Save draft (C13) và publish (C14), dùng version thực từ response:

```json
{"expectedContentVersion":0,"expectedRevisionVersion":0,"title":"Oracle UCP báo timeout khi lấy connection","bodyMarkdown":"Bổ sung bước tái hiện và thông báo lỗi đã che thông tin kết nối.","summary":"Cập nhật câu hỏi.","context":{"environment":"dev"},"assets":[]}
```

```json
{"expectedContentVersion":1,"expectedRevisionVersion":1}
```

Accept và bỏ accept (C07):

```json
{"expectedContentVersion":2,"answerContentId":102,"answerRevisionId":205}
```

```json
{"expectedContentVersion":3,"answerContentId":null,"answerRevisionId":null}
```

ContentDetail có contentId,kind,ownerOrgId,author(userId,displayName),maintainer,visibility,lifecycleStatus,contentVersion,publishedRevision,draftRevision? và capabilities(canEditDraft,canPublish,canAnswer,canComment,canVote,canAccept,canManageAccess,canReport). RevisionDetail gồm revisionId,revisionNo,revisionVersion,title,bodyMarkdown,summary,context,revisionStatus,publishedAt,assets[]. Q bổ sung resolutionStatus,acceptedAnswerId,acceptedRevisionId,acceptedRevisionIsCurrent,answerCount,score,myVote,tags. AssetView có assetId,revisionAssetId,originalName,mimeType,sizeBytes,mediaType,status,durationSeconds/pageCount đã kiểm; không key/provider ID/hash request. Capabilities chỉ gợi ý UI, backend kiểm lại mỗi ghi.

Envelope C07 minh họa với data rút gọn (response thực trả Q đầy đủ):

```json
{"responseId":"community-example-001","timestamp":"2026-10-27T00:00:00Z","status":"SUCCESS","messageCode":"CORE-SUCCESS","message":"Thao tác thành công.","messageTitle":"Thành công","data":{"contentId":101,"contentVersion":3,"resolutionStatus":"solved","acceptedAnswerId":102,"acceptedRevisionId":205,"acceptedRevisionIsCurrent":true}}
```

List đặt `{items:[],page:0,size:20,totalElements:0,totalPages:0}` trong data của envelope. Ẩn/xóa/không có quyền biết resource đều CORE-RESOURCE_NOT_FOUND, không dùng lỗi khác nhau để dò ID; endpoint yêu cầu role mà caller thiếu trả 403.

<a id="media"></a>
## Upload, worker và cleanup

Đây là Community mới, không đổi quyền/lifecycle Learning. Tái sử dụng adapter storage S3 và tách helper probe/hash/stream khi code; không gọi repository Learning. Storage metadata Community riêng, manifest ở community_asset_upload_parts, không dùng JSON để thay các dòng part.

- Toàn bộ: PNG/JPG/JPEG/WEBP tối đa 5.000.000 byte, PDF 20.000.000, DOCX/XLSX/PPTX 10.000.000. Không SVG/HTML/executable hoặc extension giả MIME. JDL/text UTF-8 tối đa 1.000.000 byte, không NUL; không thực thi. Video MP4/WEBM<=500.000.000 byte, duration<=86.400 giây, codec/probe theo pipeline Learning hiện có sau khi tách helper; không bỏ kiểm.
- requestId trở thành upload_id; cùng uploader/metadata trả cùng phiên, khác metadata lỗi idempotency. Phiên 24 giờ, part 8 MiB, phần cuối đúng số byte còn lại. Hash hex 64 chuẩn hóa lowercase. UUID/key không thay quyền.
- Một (asset,partNumber) có size/hash bất biến. Reserve dưới khóa, IO S3 ngoài transaction, ACK sau kiểm token/version còn đúng. Retry part đã uploaded cùng hash trả ACK cũ, không cộng uploadedBytes lần hai; khác hash/size bị từ chối. Part còn reserved trả UPLOAD_BUSY + retryAfterSeconds.
- Reservation hết hạn theo timeout S3 cộng grace 30 giây; chưa hết timeout không cấp lần ghi khác. Worker/request cũ mất token không commit ACK. Complete cần đủ part uploaded, không reservation còn sống, manifest DB khớp provider; kiểm whole-file SHA-256 sau complete. ETag không thay SHA-256, ghi muộn/bytes sai không được ready.
- UploadView: uploadId,assetId,status,partSize,totalBytes,uploadedBytes,expiresAt,parts[{partNumber,sizeBytes,checksumSha256,status}],processingErrorCode?,retryAfterSeconds?. Không trả etag/key/provider ID/lease.
- C44 chỉ uploader được poll; sau khi asset đã attach còn phải có quyền đọc ít nhất một revision hợp lệ đang dùng file. Không dùng manifest/status route để mở metadata tài nguyên khi đã mất ACL.
- State: uploading → completing → processing → ready/failed; uploading → aborted/expired. Completing/processing không abort trực tiếp. Complete gọi lại trả trạng thái, ready không chạy lại; mất ACK S3 complete phải đối soát đúng object trước thử lại, không tạo object mới.
- Init lại requestId của phiên aborted/expired vẫn trả phiên cũ và trạng thái đó; muốn upload lượt mới phải dùng UUID mới. Không hồi sinh phiên cũ hoặc đổi hash để tái sử dụng file khác.
- Worker lease 300 giây, gia hạn mỗi 60 giây, IO/probe ngoài transaction, ghi kết quả chỉ khi token còn đúng. Retry lỗi hạ tầng tối đa 3 lần, backoff 30 rồi 120 giây; checksum/codec/file sai không retry tự động. Complete trên failed chỉ mở lượt retry cho lỗi hạ tầng được allowlist và bytes còn, không sửa metadata/hash để vượt kiểm.
- Kiểm signature/MIME/checksum thật, Office ZIP hợp lệ, PDF page count thật, video codec/duration thật trước ready. Draft cũng chỉ attach ready chưa xóa mềm, có quyền công bố nguồn; đọc được private file không đồng nghĩa được copy sang nội dung rộng hơn.
- Xóa tệp còn ở bất kỳ revision nào, kể cả superseded/soft-deleted còn lịch sử, bị chặn. GET/HEAD qua revisionAssetId kiểm parent ACL mỗi lần. Một byte range trả 206; sai/nhiều range trả 416; ETag/If-Range/Accept-Ranges/Content-Disposition theo contract file hiện có. Không có signed URL dài hạn; mất quyền chặn Range tiếp theo.

Cleanup worker mới chỉ bật bằng cấu hình được review; không thay cleanup Learning. Upload expired/aborted đưa cleanup_status=pending và abort đúng provider multipart ID; giữ metadata/manifest để đối soát. Ready không có tham chiếu được hẹn cleanup sau 72 giờ từ ready hoặc lần mất tham chiếu cuối. Tệp còn revision cũ/soft-deleted không được dọn để giữ lịch sử.

Cấu hình Community tách namespace app.community.media, mặc định part-size=8 MiB, upload-expiry=24h, processing-lease=300s, processing-heartbeat=60s, probe-timeout=120s, poll-ms=3000, reservation-grace=30s, cleanup-grace=72h, max-attempts=3. Timeout IO lấy từ storage client thực và được kiểm khi tính reservation; không ghi cứng lease ngắn hơn timeout IO. processing_available_at/cleanup_after lưu lịch retry; processing_attempts/cleanup_attempts lưu giới hạn lượt, không reset bằng restart. Complete retry lỗi hạ tầng do caller mở lượt mới có audit, không cho reset lỗi vĩnh viễn.

Attach và cleanup khóa cùng asset: kiểm lại mọi revision_assets rồi đánh dấu deleted_at + cleanup lease trước IO để chặn attach mới. Chỉ xóa đúng key/rendition của asset, không quét prefix chung. Lỗi S3 giữ cleanup_status=failed, retry tối đa 3 lần; mất ACK DELETE thì retry đúng key an toàn. Worker mất lease không ghi kết quả. Không tự hard-delete bảng/content, không thay retention prod bằng script này. Nghiệm thu race attach/delete trước khi bật worker.

## Thông báo và tìm kiếm

Đặc tả toàn app/digest/AI nằm ở phần mở rộng. Sự kiện nghiệp vụ ghi outbox cùng transaction; worker kiểm quyền ở fanout và API kiểm lại khi đọc. Payload chỉ ID/event type, không title/excerpt/file URL riêng tư. Worker bật phải có app.community.worker.actor-id (1..20 ký tự) được cấu hình và trusted context tường minh; thiếu thì fail fast, không tự dùng SYSTEM/anonymous hoặc tạo account. Không gửi email/Slack trong phạm vi này, thông báo/bản tin là in-app.

## Mã lỗi nghiệp vụ dự kiến

Các mã dưới đây trả HTTP 200/ERROR; cần enum/Swagger khi code. Thiếu token 401, thiếu role rõ ràng 403, lỗi giao thức giữ 400/415/416, lỗi hệ thống 500. Validation tổng quát CORE-VALIDATION_FAILED; chi tiết field theo guideline. Một errors item thì message ngoài trùng thông báo item. Không trả S3 key/SQL/exception/secret.

| messageCode | Ý nghĩa | errors.field |
|---|---|---|
| COMMUNITY-CONFLICT | Version cũ, ghi đồng thời | expectedContentVersion/expectedRevisionVersion/expectedVersion |
| COMMUNITY-IDEMPOTENCY_CONFLICT | Dùng lại key khác payload | clientRequestId/requestId |
| COMMUNITY-DUPLICATE | Slug/alias đã có | slug/aliases[index] |
| COMMUNITY-STATE_INVALID | Trạng thái không cho thao tác | action/contentId |
| COMMUNITY-ACCEPT_INVALID | Answer/revision không hợp lệ | answerContentId/answerRevisionId |
| COMMUNITY-ASSET_NOT_READY | Tệp chưa kiểm hoặc đang xóa | assets[index].assetId |
| COMMUNITY-UPLOAD_BUSY | Reservation đang còn hạn | partNumber |
| COMMUNITY-UPLOAD_EXPIRED | Phiên đã hết hạn | uploadId |
| COMMUNITY-FILE_INVALID | Size/hash/loại file sai | checksumSha256/file |
| COMMUNITY-SELF_VOTE | Tự vote | value |

## Nghiệm thu

Checklist dưới đây **chưa phải kết quả test**. Không sinh source test khi chưa được yêu cầu; kiểm API thật chỉ bằng dữ liệu/tài khoản được cho phép.

| ID | Tình huống | Kết quả bắt buộc |
|---|---|---|
| AC01 | Tìm trước rồi hỏi khi không có hit | Tạo draft được, publish kiểm tag |
| AC02 | Retry POST content/comment cùng key/payload | Một resource, không thêm revision/outbox |
| AC03 | Key cũ khác payload/actor | Conflict cùng actor; không lộ resource actor khác |
| AC04 | Hai vote +1 đồng thời, SET 0 lặp | Một vote; không tăng/giảm hai lần |
| AC05 | Tự vote hoặc parent closed/archived | SELF_VOTE/STATE_INVALID, score không đổi |
| AC06 | Accept answer question khác/revision vừa thay | Từ chối, accept cũ không đổi |
| AC07 | Hai accept khác nhau cùng version | Một thắng, một conflict |
| AC08 | Sửa answer đã accept | Accept giữ revision cũ, DTO báo khác current |
| AC09 | Hide/delete accepted answer đồng thời accept | Không trỏ bản ẩn, audit/outbox nhất quán |
| AC10 | Answer còn draft | Không tăng count/resolution, không hiện reader list |
| AC11 | Hai editor sửa cùng draft version | Một thắng, không ghi đè im lặng |
| AC12 | Editor publish/đổi ACL; A sửa answer người khác | Từ chối, không đổi dữ liệu |
| AC13 | Đăng answer khi closed | Từ chối; accept answer cũ vẫn được |
| AC14 | A reopen question moderator đã đóng | Từ chối; moderator cần case/version |
| AC15 | Publish tệp chưa ready | Không đổi published pointer |
| AC16 | Reader gọi draft/history/includeOwnDraft | Không lộ bản chưa publish |
| AC17 | Rời Guild/đổi org/revoke grant đã bookmark | Mất nội dung/file/count/notification ở request kế tiếp |
| AC18 | User bị khóa với JWT cũ | Không đọc/ghi; giữ lịch sử đóng góp |
| AC19 | Private content qua search/tag/suggestion | Không title/snippet/count trái quyền; page/count đúng |
| AC20 | Uploader mở tệp attach khi mất parent ACL | Từ chối, không bypass bằng assetId |
| AC21 | Comment parent khác content/cycle/quá ba cấp | Validation, không tạo cây sai |
| AC22 | Xóa comment có reply | Tombstone, giữ reply hợp lệ, không lộ body cũ |
| AC23 | Retry help request/report đang mở | Trả bản hiện tại, không spam case/lời mời |
| AC24 | Moderator sai org/chưa assign xem evidence | Từ chối, không excerpt private |
| AC25 | Merge tag cycle/cross-org/alias trùng | Từ chối nguyên tử hoặc gộp đúng grain |
| AC26 | Restore khi đã có action/version mới | Conflict, không phục hồi ACL cũ |
| AC27 | Outbox mất ACK chạy lại | Một notification/event/recipient |
| AC28 | Mất quyền sau fanout trước đọc | Không title/link private trong API notification |
| AC29 | Mất mạng part trước ACK | Resume/retry cùng hash an toàn, byte count không tăng hai lần |
| AC30 | Request cũ ghi sau hết reservation | Không ACK token cũ; checksum whole-file chặn bytes lệch |
| AC31 | Complete thiếu part/reservation/hash sai | Không ready, có trạng thái/error đối soát |
| AC32 | S3 complete xong DB mất ACK | Đối soát object, không tạo file mới |
| AC33 | Worker chết/mất lease | Worker mới nhận sau hạn, worker cũ không commit |
| AC34 | Executable đổi đuôi PNG/PDF, ZIP lỗi/PDF mã hóa | Không ready, không phục vụ bytes chưa kiểm |
| AC35 | GET/HEAD Range đúng/sai, mất quyền giữa request | Header/binary đúng, 416 đúng, mất quyền bị chặn |
| AC36 | Cleanup race attach | Một thắng, không attachment trỏ tệp đã xóa |
| AC37 | Cleanup tệp còn revision cũ/xóa mềm | Không xóa bytes/history |
| AC38 | S3 DELETE lỗi/mất ACK | Giữ metadata, retry đúng key, không quét prefix |
| AC39 | Đổi Radar stage/phạm vi Wiki đã duyệt (phạm vi Guild/Wiki) | Revision mới cần duyệt lại |
| AC40 | Contributor tự duyệt (phạm vi Guild/Wiki) | Từ chối theo tác giả/audit, không chỉ người sửa cuối |
| AC41 | Review JDL v1 rồi publish v2 (phạm vi review) | Thread giữ revision/file/anchor v1 |
| AC42 | Chốt review còn thread open bản chốt (phạm vi review) | Từ chối, revision chốt phải thuộc proposal |



## API mở rộng cho toàn bộ phạm vi

Tất cả dưới cùng prefix, cùng envelope/validation/quyền ở phần nền; không có route nào được xem là đã triển khai. C12–C19/C25–C28 được dùng lại cho mọi kind phù hợp. Các route tạo subtype nhận CreateContent cùng metadata chuyên biệt, không nhận owner/author tự khai.

| ID | Method / đường dẫn | Request/query và quyền | data thành công |
|---|---|---|---|
| C50 | GET `/guilds` | q,visibility,mine,page,size | `P<GuildView>` còn quyền |
| C51 | POST `/guilds` | CreateGuild(clientRequestId,slug,name,description,visibility,joinPolicy) | GuildView + membership owner |
| C52 | GET `/guilds/{guildId}` | Không | GuildView/capabilities |
| C53 | PUT `/guilds/{guildId}` | expectedVersion,name,description,visibility,joinPolicy,status | GuildView; owner/OM |
| C54 | POST `/guilds/{guildId}/join` | Không | MembershipView, active hoặc pending theo policy |
| C55 | POST `/guilds/{guildId}/invitations` | userIds<=100,expiresAt | MembershipView[] trạng thái invited; owner/GM |
| C56 | PUT `/guilds/{guildId}/members/{userId}` | expectedVersion,action approve/reject/remove/change_role,memberRole? | MembershipView; owner/GM theo quyền |
| C57 | POST `/guilds/{guildId}/invitation-response` | expectedVersion,action accept/decline | MembershipView của caller |
| C58 | POST `/guilds/{guildId}/leave` | expectedVersion | MembershipView left, không cho owner cuối rời |
| C59 | POST `/guilds/{guildId}/transfer-owner` | expectedVersion,successorUserId | GuildView; đổi owner nguyên tử |
| C60 | GET `/guilds/{guildId}/members` | q,status,role,page,size | `P<MembershipView>` trong quyền |
| C61 | GET `/guilds/{guildId}/feed` | kind,tagIds,page,size | `P<ContentCard>` lọc ACL |
| C62 | GET `/guilds/{guildId}/wiki/pages` | parentPageId?,q,page,size | `P<WikiPageCard>`; phân trang từng cấp |
| C63 | POST `/guilds/{guildId}/wiki/pages` | CreateWiki theo CreateContent + parentPageId,slug,sortNo | C draft |
| C64 | PUT `/wiki/pages/{contentId}/location` | expectedContentVersion,parentPageId,slug,sortNo | WikiPageView; chặn cycle |
| C65 | POST `/contents/{contentId}/submit` | expectedContentVersion,expectedRevisionVersion | R submitted bất biến |
| C66 | POST `/revisions/{revisionId}/decisions` | expectedContentVersion,expectedRevisionVersion,decision,note | ApprovalView + C; OM không đóng góp revision |
| C67 | GET `/revisions/{revisionId}/approvals` | page,size | `P<ApprovalView>` còn quyền revision |
| C68 | POST `/contents/{contentId}/maintenance-reviews` | expectedContentVersion,revisionId,outcome,note,nextReviewDueAt? | MaintenanceReviewView |
| C69 | GET `/maintenance/review-due` | guildId?,kind,dueBefore,page,size | `P<ContentCard>` cần rà soát còn ACL |
| C70 | GET `/guilds/{guildId}/radar` | stage?,q,page,size | `P<RadarCard>` từ revision đã duyệt |
| C71 | POST `/guilds/{guildId}/radar` | CreateRadar theo CreateContent/context.radar | C draft |
| C72 | GET `/recordings` | q,tagIds,guildId,speaker,heldFrom,heldTo,page,size | `P<RecordingCard>` |
| C73 | POST `/recordings` | CreateRecording gồm primary_video ready và context.recording | C draft |
| C74 | POST `/assets/{assetId}/transcripts` | clientRequestId,language,bodyText,segments | TranscriptView draft |
| C75 | PUT `/transcripts/{transcriptId}` | expectedVersion,language,bodyText,segments | TranscriptView draft |
| C76 | POST `/transcripts/{transcriptId}/publish` | expectedVersion | TranscriptView, enqueue reindex mọi current revision dùng asset |
| C77 | GET `/revision-assets/{revisionAssetId}/transcript` | Không | TranscriptView published hiện tại nếu có ACL |
| C78 | GET `/reviews` | q,guildId,status,page,size | `P<ReviewCard>` |
| C79 | POST `/reviews` | CreateReview gồm problem,constraints,decisionsNeeded,design_file | C draft |
| C80 | POST `/reviews/{contentId}/invitations` | reviewerUserIds,guildIds,expiresAt? | InvitationView[] snapshot user khác nhau còn quyền đọc |
| C81 | PUT `/review-invitations/{invitationId}` | expectedVersion,action accept/decline/complete/cancel | InvitationView; theo actor hợp lệ |
| C82 | POST `/reviews/{contentId}/threads` | clientRequestId,revisionId,revisionAssetId?,anchor,bodyMarkdown | ThreadView + comment gốc |
| C83 | PUT `/review-threads/{threadId}` | expectedVersion,action resolve/reopen,note | ThreadView |
| C84 | POST `/reviews/{contentId}/conclude` | expectedContentVersion,concludedRevisionId,conclusion | ReviewView; không còn thread open bản chốt |
| C85 | POST `/reviews/{contentId}/close` | expectedContentVersion,reason | ReviewView closed |
| C86 | POST `/reviews/{contentId}/follow-up` | CreateReview(clientRequestId) + sourceRevisionId | C mới + related link follow_up |
| C87 | GET `/experts` | q,tagIds,guildId,available,page,size | `P<ExpertView>` cùng org, không email/PII nội bộ |
| C88 | GET `/users/{userId}/expertise` | Không | ExpertiseView[] cùng org |
| C89 | PUT `/me/expertise` | expectedPreferenceVersion,items[{tagId,description,availableForHelp}] | ExpertiseView[] + preferenceVersion |
| C90 | GET `/knowledge-requests` | q,status,guildId,page,size | `P<KnowledgeRequestCard>` |
| C91 | POST `/knowledge-requests` | CreateKnowledgeRequest theo CreateContent + dueAt? | C draft/open sau publish |
| C92 | POST `/knowledge-requests/{contentId}/actions` | expectedContentVersion,action claim/release/fulfill/close/reopen,fulfillmentContentIds?,reason? | KnowledgeRequestView |
| C93 | GET `/lessons-learned` | q,tagIds,guildId,page,size | `P<ContentCard>` |
| C94 | POST `/lessons-learned` | CreatePostmortem theo CreateContent/context.lessonLearned | C draft |
| C95 | GET `/contents/{contentId}/links` | relationType,page,size | `P<LinkView>` cả nguồn/đích còn ACL |
| C96 | PUT `/contents/{contentId}/links` | expectedContentVersion,links[{relationType,targetContentId?/targetCourseId?/targetKnowledgeDocumentId?}] | LinkView[]; không ghi trực tiếp fulfills/follow_up |
| C97 | GET `/search` | q,sourceTypes,kind,tagIds,guildId,page,size,sort | SearchPageView hợp nhất Community/Learning/Knowledge theo quyền |
| C98 | GET `/feed` | followingOnly,guildId,kind,page,size | `P<ContentCard>` có ACL |
| C99 | GET `/me/preferences` | Không | PreferencesView, tạo mặc định dưới khóa user |
| C100 | PUT `/me/preferences` | expectedVersion,timeZone,digestFrequency,digestLocalTime,digestWeekday?,notificationPreferences | PreferencesView |
| C101 | POST `/me/onboarding` | expectedVersion,tagIds,guildIdsToFollow,complete | PreferencesView + subscriptions; không tự join Guild |
| C102 | GET `/collections` | q,mine,visibility,page,size | `P<CollectionView>` còn ACL |
| C103 | POST `/collections` | clientRequestId,name,description,visibility,primaryGuildId?,grants | CollectionView |
| C104 | GET `/collections/{collectionId}/items` | page,size | CollectionView + `P<CollectionItemView>` lọc quyền từng item |
| C105 | PUT `/collections/{collectionId}` | expectedVersion,name,description,visibility,primaryGuildId?,grants,status | CollectionView; owner, không sửa collection mặc định |
| C106 | PUT `/collections/{collectionId}/items/{contentId}` | expectedCollectionVersion,saved,note?,sortNo | CollectionItemView/null + collectionVersion; editor |
| C107 | DELETE `/collections/{collectionId}` | expectedVersion query | null; archive, không xóa nội dung |
| C108 | GET `/me/digests` | page,size | `P<DigestView>` của caller |
| C109 | GET `/me/digests/{batchId}` | page,size | DigestView + notifications còn ACL |
| C110 | POST `/me/digests/{batchId}/read` | Không | DigestView; chỉ đánh dấu các item caller còn quyền xem |
| C111 | GET `/me/contributions` | from,to,page,size | `P<ContributionView>` + tổng điểm hợp lệ của caller ở org hiện tại |
| C112 | GET `/leaderboard` | from,to,guildId?,page,size | `P<ContributorRank>` chỉ nguồn organization còn hiển thị |
| C113 | GET `/badges` | q,status,page,size | `P<BadgeView>` trong org |
| C114 | POST `/badges` | code,name,description,ruleVersion,rule | BadgeView; OM, luật allowlist bất biến khi active |
| C115 | POST `/badges/{badgeId}/retire` | expectedVersion,reason | BadgeView retired, không tự xóa lịch sử award |
| C116 | GET `/users/{userId}/badges` | page,size | `P<BadgeAwardView>` đủ điều kiện từ nguồn public cùng org |
| C117 | POST `/moderation/contributions/reconcile` | requestId,userIds?,from,to | JobView; OM đúng org, không sửa ledger tay |
| C118 | GET `/analytics/overview` | from,to,guildId? | AnalyticsSummary có ACL |
| C119 | GET `/analytics/unanswered` | olderThanDays,guildId?,page,size | `P<QuestionCard>` chưa có answer, không tính draft |
| C120 | GET `/analytics/stale` | guildId?,kind,dueBefore,page,size | `P<ContentCard>` lỗi thời/cần rà soát còn ACL |
| C121 | GET `/analytics/activity` | from,to,bucket day/week,guildId? | ActivityPoint[] có ACL |
| C122 | POST `/contents/{contentId}/view` | Không | null; một unique view/user/content/ngày UTC |
| C123 | GET `/course-revisions/{courseRevisionId}/resources` | lessonId?,page,size | `P<LearningResourceView>`; phải qua cả Learning và Community ACL |
| C124 | PUT `/course-revisions/{courseRevisionId}/resources` | expectedCourseRevisionVersion,items[{lessonId?,contentRevisionId,sortNo}] | LearningResourceView[]; chỉ khóa học draft có quyền sửa |
| C125 | GET `/learning/reference-candidates` | q,kind,page,size | `P<ContentCard>` có quyền công bố vào tài liệu khóa học |
| C126 | POST `/ai/answers` | requestId,question,sourceTypes,guildId?,history? | **Tạm dừng — 404**, giữ thiết kế AIAnswerView |
| C127 | GET `/ai/turns/{turnId}/sources` | page,size | **Tạm dừng — 404**, giữ thiết kế `P<AISourceView>` |
| C128 | PUT `/ai/turns/{turnId}/feedback` | expectedVersion,helpful,reasonCode?,note? | **Tạm dừng — 404**, giữ thiết kế AIFeedbackView |
| C129 | POST `/moderation/search/reindex` | requestId,contentIds?,guildId? | JobView; OM cùng org, không mở ACL |
| C130 | GET `/jobs/{eventId}` | Không | JobView tối thiểu của người tạo/OM đúng org |
| C131 | GET `/contents/{contentId}/maintenance-reviews` | page,size | `P<MaintenanceReviewView>` còn ACL |
| C132 | PUT `/wiki/pages/{contentId}/review-schedule` | expectedContentVersion,reviewDueAt? | WikiPageView; A/M, không thay metadata đã duyệt |
| C133 | PUT `/guilds/{guildId}/pins/{contentId}` | pinned boolean | PinView; owner/GM, không cấp quyền nội dung |
| C134 | GET `/moderation/revision-queue` | kind wiki/radar,guildId?,page,size | `P<RevisionCard>` submitted trong scope duyệt, audit |
| C135 | PUT `/contents/{contentId}/maintainer` | expectedContentVersion,maintainerUserId | C; A chỉ định người active cùng org, không chuyển tác giả/accept |
| C136 | PUT `/questions/{questionId}/duplicate` | expectedContentVersion,targetQuestionId hoặc null,reason,reportId? | Q; A tự đánh dấu hoặc moderator case hợp lệ |
| C137 | POST `/me/digests/{batchId}/retry` | expectedVersion | DigestView; failed mở lượt retry có audit, delivered trả hiện tại |
| C138 | GET `/me/assets` | status,mediaType,page,size | `P<UploadSummaryView>` uploader sở hữu, sau attach còn phải có parent ACL |

## DTO và hành vi của toàn bộ phạm vi

Tất cả list dùng cùng PageResponse, size 1..100, kiểm enum/sort và ACL trước pagination. Query mới không ghi trong bảng mặc định là optional. Body không có field actor/author/ownerOrg; org luôn lấy từ caller hoặc tài nguyên cha đã kiểm quyền. Loại nội dung do endpoint xác định, không nhận kind tùy ý.

| Nhóm request | Field bắt buộc/giới hạn và quy tắc |
|---|---|
| Guild C51/C53 | slug 3..100 dạng lowercase `[a-z0-9-]`, name 3..255, description<=10.000, visibility organization/private, joinPolicy open/approval/invite; C51 có clientRequestId, C53 có expectedVersion. Tạo Guild làm caller thành owner active cùng transaction. |
| Membership/invite | userIds khác nhau<=100 cùng org, active; expiresAt mặc định 7 ngày và tối đa 30 ngày. Role owner chỉ đổi qua transfer-owner; GM không nâng chính mình hoặc quản trị owner/GM khác. Join/invite lặp trả membership hiện tại, không nhân dòng; invitation-response chỉ người được mời. |
| Wiki/Radar | CreateContent + guildId từ path. Wiki có slug,parentPageId?,sortNo>=0, context.wiki={documentClass:guide/convention/template/standard,approvalScope?}; standard phải có scope. Radar có context.radar={technologyName 1..255,technologyVersion?<=255,stage:assess/trial/adopt/hold,approvalScope 1..3.000,effectiveFrom? YYYY-MM-DD}. |
| Submit/decision | Hai version content/revision; submitted bất biến. Decision approved/changes_requested/rejected, note<=3.000 và bắt buộc nếu yêu cầu sửa/từ chối. OM không là tác giả hoặc từng sửa revision. Approved publish pointer trong cùng transaction; changes_requested/rejected đóng bản submitted, sửa bằng draft mới. C14 không publish thẳng Wiki/Radar để vượt duyệt. |
| Maintenance | revisionId phải là current published, outcome=current/needs_update/obsolete, note<=3.000, nextReviewDueAt? date. A/M/OM được ghi; ghi nhận điểm chỉ khi OM không đóng góp revision xác nhận current. review-schedule thay lịch vận hành, không thay metadata đã duyệt. |
| Recording | CreateContent + context.recording={heldAt ISO timestamp,speakers:[{displayName<=255,userId?}],description<=10.000}; 1..20 speakers. Một primary_video ready, attachment/inline_image tùy chọn. Thời lượng lấy từ asset đã probe, không tin client. |
| Transcript | clientRequestId khi tạo; language<=100, bodyText<=1.000.000, segments tối đa 10.000 phần {startSeconds,endSeconds,text<=10.000}, sắp tăng dần, không chồng, 0<=start<end<=duration. A/M của tất cả nơi đang dùng file phải cho phép công bố hoặc uploader khi chưa attach; không sửa transcript dùng chung để thay nội dung của người khác. Publish làm bản cũ superseded; không sửa published. |
| Architecture Review | CreateContent + context.review={problem 20..10.000,constraints<=20.000,decisionsNeeded 1..20 mục}; design_file là PDF/ảnh/Office/JDL hợp lệ hoặc mô tả Markdown. C14 publish nội dung chưa mở review; C80 tạo lời mời sau khi proposal open; chuyển draft→open qua C17 action=open_review và revision phải published. |
| Review invite/thread | Reviewer IDs/Guild IDs khác nhau, resolve snapshot tối đa 500 người và tất cả phải đã có quyền đọc; không âm thầm cấp ACL hoặc mời người không có quyền. Thread cần clientRequestId, revisionId đã publish của proposal, revisionAssetId? thuộc revision, anchor={type:section/line/page/region,...}; dòng/trang bắt đầu 1, region chuẩn hóa 0..1, body 1..10.000. C26 comment nhận reviewThreadId và tự resolve revision; không cho client đổi revision khác thread. |
| Review resolve/conclude | Thread creator, A/M hoặc reviewer được mời có thể resolve/reopen khi proposal còn open/in_review; lưu actor/reason. Conclude cần conclusion 20..20.000 và concludedRevisionId đúng bản hiện tại, không thread open trên bản đó. Closed/concluded chỉ đọc; đánh giá lại dùng follow-up mới, có link follow_up bất biến. |
| Expertise | Tối đa 50 tag active cùng org, mỗi description<=3.000, availableForHelp boolean. C89 dùng version preferences để serialize replace toàn danh sách, không nhận userId khác caller. Danh bạ chỉ kỹ năng tự khai, không suy là chứng chỉ/quyền hệ thống. |
| KnowledgeRequest | CreateContent + dueAt? tương lai, subtype request_status=open sau publish. Claim chỉ chính caller đang đọc được và request open; release chỉ người claim hoặc A/M; fulfill chỉ người claim/A/M, có 1..20 fulfillmentContentIds published; close/reopen chỉ A/M có reason. Chuyển state cần expectedContentVersion, không claim đè người khác. |
| LessonLearned | context.lessonLearned={occurredOn date,symptoms 20..10.000,rootCause 20..10.000,resolution 20..20.000,prevention 20..10.000}; không nhận mã khách hàng, credential hoặc log thật chưa che. Giữ draft/publish/revision/tag/ACL như bài viết. |
| Collection | name 1..255,description<=10.000,visibility private/organization/guild/restricted; guild yêu cầu primaryGuildId, restricted đúng một user/Guild mỗi grant(reader/editor), tối đa 100. Owner quản metadata/ACL/archive; editor chỉ item. C103 có clientRequestId; C105 và C106 kiểm version collection, ghi item và version nguyên tử. Không share collection mặc định. |
| Preferences | IANA timeZone phải hợp lệ; digestFrequency off/daily/weekly, digestLocalTime HH:mm:ss, digestWeekday 1..7 chỉ với weekly; notificationPreferences chỉ allowlist event không bắt buộc. Onboarding tagIds<=30,guildIdsToFollow<=30 là theo dõi có ACL, không tự join; complete lặp không tạo subscriptions hai lần. |
| Badge definition | code 3..100,ruleVersion>=1,rule={metric:accepted_answers/received_upvotes/verified_maintenance,threshold 1..100.000}; không nhận expression/script. Active rule bất biến; sửa luật tạo bản code/ruleVersion mới, không sửa award cũ. OM quản lý, không tự trao tay để vượt rule. |
| Learning resources | <=100 item khác nhau theo lesson/revision, contentRevisionId từng published còn ACL và có quyền công bố nguồn. lessonId phải thuộc courseRevisionId. C124 chỉ sửa khóa draft, tăng version revision Learning kể cả chỉ đổi danh sách link; khóa published không được sửa. |
| AI answer | requestId UUID,question 1..2.000,sourceTypes subset community/learning/knowledge không rỗng,guildId? chỉ lọc Community; history<=10 message role user/assistant, mỗi message<=4.000. History là dữ liệu không tin cậy, không phải nguồn trích dẫn. Tổng prompt giới hạn theo context model hiện có, không tự tải model mới. |
| AI feedback/job | Feedback expectedVersion (0 nếu chưa có),helpful boolean,reasonCode irrelevant/incorrect/outdated/missing_source/other,note<=2.000; chỉ chủ turn. Reindex/reconcile requestId UUID,contentIds/userIds<=500,from/to tối đa 366 ngày; đúng OM scope. JobView chỉ eventId,status,processedCount,failedCount,errorCode, không trả payload nội bộ. |

C134 chỉ hiển thị submitted Wiki/Radar mà caller có scope phê duyệt; submit là gửi revision đó cho org approver xem để quyết định, không mở toàn bộ lịch sử/private draft khác cho OM. Ghi audit khi mở revision duyệt, không để queue thành quyền đọc mọi private content. C135 không cấp quyền cho người khác org, caller không được đổi maintainer khi hidden/archived; OM chuyển khi A không còn quyền qua report/action C41 có audit. C136 yêu cầu đọc cả hai question, chặn self/cycle, chỉ một duplicate target; đánh dấu đóng câu hỏi và giữ answer/accept cũ. Moderator phải có report được assign trỏ đúng question. Bỏ duplicate chỉ xóa link, không tự mở câu hỏi đã bị đóng; mở lại theo C17. Link related giữa hai question ở C96 dùng question_links, không tạo bản sao đồng thời ở content_links.

Guild visibility=private yêu cầu joinPolicy=invite để không cho dò Guild ID rồi xin quyền. Invitation có hạn, expired/declined chuyển membership rejected có audit, không tạo role. Pending/invited không đọc nội dung visibility=guild. Content organization trong Guild vẫn theo quyền org, pin không làm tăng ACL. Nội dung guild/restricted không được gắn vào public feed để trả title/count cho người ngoài.

KnowledgeRequest close là request_status=closed, không cho claim/fulfill/comment mới; reopen trả open hoặc claimed nếu claim còn hợp lệ. Fulfilled vẫn có thể nhận bình luận phản hồi, nhưng thay fulfillment phải có version và kiểm audience lại. DueAt không tự giao người khác hoặc tự chốt fulfilled. Các hành động này và links fulfills ghi nguyên tử với contentVersion/outbox.

Các dữ liệu không phải public operation: đổi first/last view timestamp, cấp điểm/award, index vector, gán lease/provider ID và clock digest đều do backend xác định. Không có endpoint sửa trực tiếp điểm/answerCount/viewCount.

Giới hạn transport JSON Community là 8 MiB sau giải nén, vượt trả HTTP 413 bằng envelope lỗi giao thức; không áp vào octet-stream part C45 vì part đã có giới hạn byte riêng. Transcript kiểm thêm tổng text trong segments<=1.000.000 ký tự và phải khớp bodyText đã chuẩn hóa, không chỉ kiểm từng segment; không nạp request không giới hạn rồi mới validate. Đây là yêu cầu lúc code middleware/config, chưa phải cấu hình đã áp dụng.

Các POST tạo subtype dùng clientRequestId như content nền; Guild, collection, transcript và review thread lưu key/hash ở bảng tương ứng theo (created_by,client_request_id). C82 tạo thread và comment gốc nguyên tử, retry không thêm comment thứ hai. Invite, help request, report dùng grain hiện tại để SET/trả bản cũ; không tự mời lại người đã declined bằng retry mất ACK. Badge tạo theo (org,code,ruleVersion), trùng luật báo DUPLICATE. Job reindex/reconcile lưu request hash trong payload tối thiểu của outbox, dedup theo actor/requestId; không chứa prompt hoặc excerpt.

## Quyền và vòng đời bổ sung

| Nghiệp vụ | Ai và điều kiện |
|---|---|
| Guild | Bất kỳ user active cùng org tạo Guild; owner quản cấu hình/role, GM quản membership thường/feed pin. Private Guild chỉ thành viên/invitee biết metadata tối thiểu cần phản hồi, không hiện feed/search ngoài ACL. OM chỉ can thiệp metadata/owner có lý do audit, không mặc nhiên đọc nội dung private. |
| Owner không còn hiệu lực | User khóa/chuyển org không bị giữ lại chỉ vì là owner. Effective ownerUnavailable làm Guild chỉ đọc ngay, owner/GM không nâng quyền qua dữ liệu membership cũ. OM đúng org transfer-owner cho active member với reason/audit, rồi mới mở ghi trở lại. Không tự xóa hoặc mở ACL. |
| Wiki/Radar | Owner/GM/A/M tạo nội dung theo ACL; editor sửa draft. Mọi Wiki/Radar phải submit và được OM độc lập duyệt trước publish. Sửa stage/version/scope/effectiveFrom tạo revision mới cần duyệt lại; vị trí Wiki/lịch rà soát không được dùng để thay metadata đã duyệt. |
| Review | A/M mở/mời/chốt; reviewer chỉ phản biện nếu invited active và đọc được proposal. Guild invite resolve snapshot, rời Guild/mất grant làm mất quyền ở request kế tiếp. Revocation invitation không xóa thread cũ; không tự xóa ACL do nguồn khác cấp. |
| Transcript | File đã dùng ở nhiều nội dung: transcript dùng chung không được sửa để tác động ngoài quyền. Khi không có quyền công bố tới toàn audience nguồn, tạo asset/transcript riêng qua quy trình có phép, không overwrite. Không thêm dịch vụ speech-to-text bên ngoài: transcript nhập thủ công trước, đọc/tìm video vẫn hoạt động bằng title/description khi chưa có transcript. |
| Collection | Collection ACL và content ACL là hai cổng độc lập. Share chỉ giúp thấy collection, không giúp đọc item private; count/page/notes lọc theo cả hai. Editor không thêm item mà chính họ không đọc được; chuyển collection public không công bố body/file nguồn. |
| KnowledgeRequest | Chỉ fulfilled nếu có ít nhất một liên kết fulfills mà audience request nằm trong audience tài liệu đích. Có thể chứng minh bằng target organization cùng org hoặc policy so sánh cùng Guild/grant reader với hạn phù hợp; không chứng minh được thì từ chối fulfill. Nguồn bị hide/revoke/thu hẹp khiến không còn fulfillment hợp lệ thì chuyển lại claimed/open qua event có audit, API vẫn kiểm quyền ngay trước khi worker bắt kịp. |
| Lessons Learned | Mọi người viết; format và review độ mới dùng chung, không bắt INSTRUCTOR. Công bố thông tin vận hành cần quyền công bố nguồn, không suy đã được phê duyệt toàn BIDV. |
| Mentor/danh bạ | Tự khai expertise/available; gửi yêu cầu hỗ trợ qua C35, không mở email/điện thoại/credential người khác. Không hứa thời gian phản hồi bằng cách tự gán người đang unavailable. |
| Link Learning | Cần đồng thời quyền quản lý draft của Learning và quyền công bố revision Community. Learner cần đồng thời quyền học đúng enrollment/revision và Community ACL. Link không copy bytes/body vào khóa để vượt ACL; nguồn bị ẩn/mất quyền thì không trả nhãn hoặc file. |

Source content link relation thêm follow_up, related, summarizes, supersedes, fulfills, learning_reference; fulfills chỉ qua knowledge-request action, follow_up chỉ qua review follow-up. Chặn self-link/cycle đối với supersedes/follow_up. Wiki parent cùng Guild, membership/ACL/link đều dùng ID scalar không FK vật lý.

## Onboarding, mentions và digest

Onboarding chọn tag/Guild quan tâm, hướng dẫn tìm trước/hỏi sau và lưu completed_at. Không ép tham gia Guild hoặc tự cấp grant; private Guild chỉ được chọn khi đã có quyền xem metadata thích hợp. Default timeZone=Asia/Ho_Chi_Minh,digestFrequency=off, giờ 08:00.

Markdown mention dùng token có userId được parser cho phép (ví dụ `@[Tên hiển thị](user:000004)`); tên lấy từ hồ sơ, không tin label client. Parse AST ngoài code block, tối đa 30 người/revision/comment, cùng org và phải đọc được nguồn; community_mentions có đúng một revision_id/comment_id. Draft không gửi notification. Khi publish/chỉnh comment chỉ gửi cho người mới được thêm so với bản trước; key source/mentionedUser chặn retry. Self-mention không gửi.

Subscription mode instant/digest/muted/off: off xóa subscription, muted giữ theo dõi nhưng không phát subscription event, instant đưa vào inbox ngay, digest đợi batch. Nếu digest được chọn mà user preferences frequency=off, API từ chối để user chốt lịch thay vì âm thầm đổi mode. Direct reply/mention/invite/moderation luôn instant nếu còn quyền, gộp với subscription cùng event/recipient, không gửi hai notification. Tùy chọn người dùng được tắt loại không bắt buộc; moderation/security outcome không được che bởi preference.

Batch grain (user_id,window_start,window_end) UTC, cửa sổ nửa mở [start,end), tính giờ local theo IANA zone/DST rồi lưu UTC. Worker giữ lease, claim items chưa ở batch khác, retry không thêm hai lần. Mỗi notification tối đa một batch; thay timezone/lịch chỉ ảnh hưởng cửa sổ chưa claim, không gửi lại batch delivered. Trước delivery và trước GET batch kiểm ACL/user status lại; item không còn quyền suppressed, không có excerpt/count trái quyền. Bản tin rỗng không tạo thông báo giả. Nội dung digest là DTO dựng lúc đọc từ nguồn còn quyền, không lưu bản sao title/body riêng tư. Đánh dấu read batch chỉ tác động notification còn quyền của chính caller. Đây là in-app, không tự gửi email/Slack.

Worker digest tối đa 3 lần mỗi lượt theo lease/backoff chung; failed giữ batch/items và được chủ batch retry C137 với version, có audit. Mở lượt mới không gỡ item sang batch khác hoặc tạo bản tin trùng. Delivered retry trả trạng thái hiện tại, không giao lại. next_digest_at được tính từ cuối cửa sổ đã claim; batch cũ lỗi vẫn retry độc lập và notification đã claim không bị bỏ qua.

## Ghi nhận đóng góp, huy hiệu và thống kê

Quy tắc khởi đầu minh bạch, policy_version=1: +5 cho tác giả answer được accept, +1 cho tác giả nhận upvote hợp lệ, +3 cho maintainer được OM độc lập xác nhận một revision hiện tại đã được rà soát hữu ích. Không điểm chỉ vì đăng nhiều bài, đọc nhiều hoặc tự vote; downvote ảnh hưởng xếp hạng câu trả lời, không trừ reputation trực tiếp. Không quy đổi tiền/cấp quyền. Các giá trị này là policy cấu hình có version; đổi policy không hồi tố lịch sử tự động.

Chỉ ghi điểm cho nguồn visibility=organization cùng org, published còn hiển thị và không xóa mềm. Guild/private/restricted vẫn có thống kê cá nhân theo ACL nhưng không đưa điểm/chứng cứ của chúng lên leaderboard/huy hiệu public. user inactive không hiển thị trên leaderboard. Award rule chỉ nhận metric allowlist và threshold, không chạy code từ JSON.

Outbox xử lý theo trạng thái nguồn hiện tại dưới khóa grain thay vì cộng mù theo thứ tự event đến. Contribution ledger append-only, chống trùng (source_event,beneficiary,reason); mỗi dòng positive chỉ có một reversal bằng điểm đối dấu, trỏ reverses_event_id. Bỏ upvote, đổi/unaccept, hide/delete hoặc nguồn trở thành private thì đảo khoản tương ứng. Re-accept cùng answer khi chỉ xác nhận revision mới không nhận thêm +5. Event đến sai thứ tự phải reconcile desired state với các khoản chưa đảo, không cộng hai lần; restore nguồn có thể tạo credit mới hợp lệ, không sửa dòng cũ. Worker/restart không reset score.

Badge definition grain (org,code,rule_version); rule active bất biến, retired không xóa lịch sử. Một award hiện tại mỗi (badge,user), điều kiện không còn thì revoked có lý do/audit; đạt lại cập nhật trạng thái award cùng audit, không nhân hàng huy hiệu. Không cho OM tự award bằng body raw; reconcile chỉ tạo job đánh giá theo luật; job dedup theo actor/requestId, payload chỉ ownerOrgId/target IDs/request hash và tiến độ không nhạy cảm, khác hash trên cùng key bị từ chối. Public award/leaderboard luôn dựng lại từ nguồn đủ điều kiện hiện tại, không chờ worker mới hết lộ dữ liệu. Personal ledger cũng chỉ trả source caller còn ACL; không mở chi tiết cũ khi đã đổi org.

Analytics có các metric: số câu hỏi published, unanswered (0 answer published), answered, solved, tỷ lệ solved/questions; thời gian tới answer published đầu tiên; bài cần rà soát/lỗi thời; participation theo unique contributor; activity theo ngày/tuần và unique views. Thời gian phản hồi tính từ MIN(published_at) của question tới MIN(published_at) answer đầu, loại draft/deleted/hidden; không dùng create_time của draft. Chỉ tính trong khoảng thời gian/ACL nguồn đã chọn, denom=0 trả null cho tỷ lệ chứ không chia 0. Không chèn batch view thành contribution point. Một content/user/ngày UTC có một view, first/last do server; không lưu IP/user agent để đếm. Dashboard thường chỉ metric theo dữ liệu caller được xem, OM chỉ số riêng trong scope điều phối; không trả danh sách nội dung private qua drill-down không có case.

## Tìm kiếm thống nhất và kết nối Learning

C97 sourceTypes là subset community/learning/knowledge; mỗi module cung cấp public search adapter kiểm quyền bằng Identity/role/scope hiện có. Adapter trả projection cùng shape (sourceType,sourceId,revisionId,title,snippet,tags?,rank,updatedAt,url,capabilities), không entity/secret. Community question có answer match trả question gốc + answerId để mở đúng vị trí; không trộn ngân hàng câu hỏi thi với Q&A. Riêng C97 dùng SearchPageView gồm items,page,size,totalElements,totalPages và appliedSourceTypes; không đổi PageResponse dùng chung hoặc bọc entity module khác.

Paging/count toàn nguồn phải chính xác, không lấy 20 mỗi nguồn rồi lọc/cắt trong RAM. Do cùng PostgreSQL, chọn query-contributor port: từng module tự tạo subquery/ACL/parameters tin cậy trong repository của nó; module search orchestration hợp nhất UNION ALL projection bằng prepared query, filter/sort/count/limit ở DB. Không nhận tên bảng/SQL/scope từ request, không để Community service truy vấn repository module khác. rank chuẩn hóa ts_rank_cd/(1+ts_rank_cd), thêm sourceType/id tie-breaker; source không hỗ trợ một filter bị loại theo khả năng đã công bố, không âm thầm bỏ filter. q 1..300, sourceTypes<=3, tagIds<=10 chỉ áp dụng Community; có filter chỉ dành Community thì kết quả chỉ Community và response trả appliedSourceTypes. Quyền bảo mật mới là nguồn chuẩn, vector index không quyết định ACL.

Link tài liệu khóa học ghim content_revision_id, không tự nhảy bản mới. C124 dưới cùng transaction/khóa revision Learning, tăng version khóa khi chỉ thay resource; publish khóa gọi Community public validation port để kiểm nguồn, clone/copy draft copy links thông qua adapter có cùng quyền, không mất resource hoặc mở ACL. Runtime learner cần cả hai quyền; nguồn superseded vẫn xem được nếu từng published còn ACL và không bị ẩn/xóa, đúng mục tiêu ghim phiên bản. Muốn cập nhật nguồn tạo draft khóa mới; không sửa revision Learning đã publish. Không biến mọi tác giả Community thành INSTRUCTOR.

## AI có dẫn nguồn, index và feedback

Chỉ gọi Ollama cục bộ/cấu hình hiện có; không tự tải model hoặc gửi dữ liệu ngoài hệ thống. Community dùng namespace/index riêng trong community_search_chunks; embedding bge-m3 vector(1024), tên/phiên bản model ghi theo chunk. Nếu model/dimension sai thì job failed và phục hồi bằng reindex, không so vector khác model. Không copy Community private vào knowledge_document_chunks toàn app. Worker dùng outbox/lease, dựng đủ tập chunk mới rồi đánh dấu ready nguyên tử; event cũ chỉ index nếu revision vẫn current published. Source superseded/hidden/deleted hoặc transcript đổi làm nguồn cũ không hợp lệ ngay qua query, worker dọn/reindex sau không ảnh hưởng quyền.

Chunk có (revision,transcript?,chunk_no,embedding_model) riêng; text từ Markdown/plain transcript đã sanitize. Video chỉ có transcript thì cite mốc giây; chưa có transcript vẫn tìm title/description nhưng không bịa nội dung video. Learning/Knowledge retrieval qua adapter nguồn hiện có, chỉ current/eligible revision caller được đọc; citations trỏ đúng revision/chunk, không chỉ title.

C126 trả JSON đồng bộ AIAnswerView={turnId,status,answer,citations:[{citationNo,sourceType,sourceId,revisionId,title,url,locator}],resultRetained}. TopK mặc định 5, tối đa 10, nguồn mỗi excerpt<=2.000 ký tự; total turn timeout 180 giây gồm retrieval/model, concurrent theo cấu hình app hiện có. Không có nguồn hợp lệ thì no_sources + answer giải thích chưa tìm thấy, không gọi mô hình để tự bịa tri thức nội bộ. Metadata lượt AI lưu status/model/hash/timing/source pointers, **không lưu prompt/answer** và không log chúng.

Nguồn có maintenance outcome=obsolete cho đúng revision bị loại khỏi AI mặc định; search thường vẫn có thể hiện với nhãn lỗi thời và ACL. Answer có bản mới nhưng acceptedRevision khác current không được boost như giải pháp đã xác nhận của bản hiện tại. Learning adapter chọn đúng revision caller được học (có thể là bản enrollment cũ), không lấy bản course mới chỉ vì published pointer vừa thay. Mọi source vẫn phải đang được phép mở, không tự kéo draft hoặc metadata quản trị vào câu trả lời learner.

RequestId có at-most-once turn: đang xử lý trả turnId/status, không chạy model hai lần; cùng key khác hash lỗi idempotency. Backend có cache riêng theo actor/turn tối đa 10 phút, 100 lượt/instance, chỉ dùng nếu kiểm lại toàn bộ nguồn còn quyền. Restart/cache miss thì trả metadata + resultRetained=false,answer=null; muốn tạo câu trả lời mới dùng UUID mới, không hứa phục hồi answer đã mất. Turn processing quá 180 giây được đánh failed dưới khóa, không giữ treo mãi. Cache không phải nguồn quyền và không chia giữa user.

Trong retrieval lọc ACL và current revision **trước vector topK/count/snippet**. Trước trả response/cache, kiểm lại toàn bộ nguồn lần nữa; mất một quyền hoặc source đổi trong lúc generate thì discard toàn answer/status=discarded, không chỉ xóa citation để vẫn lộ văn bản private. Chỉ chấp nhận số citation trỏ nguồn server đã retrieve, không dùng URL/model tool calls làm quyền. Prompt và tài liệu/code snippets là dữ liệu không tin cậy; hướng dẫn trong tài liệu không được thực thi. Agent hiện có chỉ bổ sung công cụ đọc Community dưới JWT/policy; thay đổi dữ liệu vẫn theo cơ chế xác nhận của Assistant hiện có.

C127 chỉ chủ turn được mở, lọc nguồn còn quyền, không trả prompt/answer cũ. AISource đúng một communityChunkId/knowledgeChunkId/courseRevisionId, không snapshot snippet riêng tư. C128 chỉ chủ turn SET feedback, một dòng mỗi (turn,user); note optional<=2.000, không yêu cầu paste lại câu trả lời. Feedback không tự publish tri thức, đổi điểm hoặc tin một câu trả lời là chuẩn. Reindex job chỉ OM trong org, có requestId/hash/dedup trong outbox và JobView tối thiểu; job không bypass phạm vi để lấy private content cho người chạy.

## Ví dụ bổ sung

Wiki chuẩn và Radar phải submit/duyệt revision, các số ID/version là minh họa:

```json
{"clientRequestId":"ce96c729-dba0-4fb7-8347-44cc755938b0","title":"Quy ước viết API trong Guild Backend","bodyMarkdown":"Tài liệu mô phỏng để review quy ước trong Guild.","summary":"Đề xuất quy ước.","visibility":"guild","tagIds":[11],"parentPageId":null,"slug":"api-conventions","sortNo":1,"context":{"wiki":{"documentClass":"standard","approvalScope":"Nội bộ Guild Backend"}},"assets":[]}
```

```json
{"expectedContentVersion":2,"expectedRevisionVersion":1,"decision":"approved","note":"Đã rà soát đúng phạm vi áp dụng."}
```

Collection restricted và yêu cầu AI:

```json
{"clientRequestId":"702bbffd-866f-4678-8911-4e789b8ec60c","name":"Onboarding Backend","description":"Các bài cần đọc.","visibility":"restricted","primaryGuildId":null,"grants":[{"userId":"000004","permission":"reader","expiresAt":null}]}
```

```json
{"requestId":"51565691-985f-4a2f-8296-d88a38cac2c7","question":"Các câu hỏi nào đã giải quyết lỗi connection pool?","sourceTypes":["community","knowledge"],"guildId":null,"history":[]}
```

AI no_sources và citation (data rút gọn, ngoài vẫn BaseResponse):

```json
{"turnId":91,"status":"no_sources","answer":"Chưa tìm thấy nguồn phù hợp mà bạn được phép xem.","citations":[],"resultRetained":true}
```

```json
{"turnId":92,"status":"answered","answer":"Bạn có thể tham khảo cách đối soát cấu hình pool trong [1].","citations":[{"citationNo":1,"sourceType":"community","sourceId":101,"revisionId":205,"title":"Cấu hình connection pool","url":"/community/questions/101?revisionId=205","locator":{"chunkNo":1}}],"resultRetained":true}
```

## Mã lỗi bổ sung của phạm vi đầy đủ

Lỗi nghiệp vụ vẫn HTTP 200/ERROR theo contract chung; lỗi Ollama/storage/probe không dự kiến giữ lỗi hệ thống 500, quá tải/rate limit giữ 429, không che thành thành công nghiệp vụ.

| messageCode | Ý nghĩa | field |
|---|---|---|
| COMMUNITY-MEMBERSHIP_INVALID | Membership/invitation hết hạn hoặc không đủ hiệu lực | userId / guildId |
| COMMUNITY-OWNER_REQUIRED | Không cho owner cuối rời hoặc transfer tới người không hợp lệ | successorUserId |
| COMMUNITY-SELF_APPROVAL | Contributor không tự duyệt revision | decision |
| COMMUNITY-REVIEW_UNRESOLVED | Còn thread open hoặc revision chốt không hợp lệ | concludedRevisionId |
| COMMUNITY-REQUEST_CLAIMED | Yêu cầu tri thức đã được người khác nhận | action |
| COMMUNITY-FULFILLMENT_INVALID | Tài liệu không đáp ứng audience/trạng thái | fulfillmentContentIds[index] |
| COMMUNITY-DIGEST_INVALID | Múi giờ/lịch hoặc mode digest chưa hợp lệ | timeZone / digestFrequency |
| COMMUNITY-LINK_INVALID | Learning revision/lesson/source không đúng hoặc khóa đã publish | contentRevisionId / expectedCourseRevisionVersion |
| COMMUNITY-AI_SOURCE_CHANGED | Quyền hoặc nguồn đổi trong lúc tạo answer; không trả nội dung cũ | sourceTypes |
| COMMUNITY-RULE_INVALID | Luật huy hiệu/phiên bản không thuộc allowlist | rule / ruleVersion |

## Nghiệm thu bổ sung cho toàn bộ phạm vi

AC01–AC42 ở trên vẫn áp dụng. AC43–AC80 dưới đây cũng nằm trong cùng phạm vi giao hàng, không phải backlog tùy chọn; đây là đặc tả chưa phải kết quả test đã chạy.

| ID | Tình huống | Kết quả bắt buộc |
|---|---|---|
| AC43 | Tạo Guild retry, join/invite lặp | Một Guild/key, một membership hiện tại; không thêm quyền hai lần |
| AC44 | Private Guild chưa được mời/pending | Không thấy feed/member/content private; không dò tên bằng ID |
| AC45 | Owner rời/khóa/chuyển org, transfer đồng thời | Không giữ user ở org để vượt policy; effective read-only, OM chuyển owner đúng scope/audit |
| AC46 | GM tự nâng role hoặc quản trị owner | Từ chối; không sửa Identity grant |
| AC47 | Di chuyển Wiki tạo parent cycle/cross-Guild | Từ chối nguyên tử, không hỏng cây |
| AC48 | Đổi Radar stage/scope/version sau approved | Tạo revision mới và duyệt lại, không sửa dữ liệu đã duyệt |
| AC49 | Submit private Wiki, approver mở draft khác/history ngoài queue | Chỉ revision submitted được cấp quyền duyệt; không lộ revision khác |
| AC50 | Video khai duration giả/codec sai; transcript vượt duration | Không ready/publish transcript, không tin metadata FE |
| AC51 | Transcript dùng chung bị sửa bởi người chỉ có quyền một nơi | Không thay transcript ngoài quyền; không làm đổi private content khác |
| AC52 | Mời cả Guild rồi membership thay đổi | Giữ snapshot lời mời nhưng quyền đọc lấy hiện tại, không auto grant |
| AC53 | Thread anchor thuộc file/revision khác | Từ chối, giữ góp ý đúng bản nguồn |
| AC54 | Close/conclude/follow-up retry | Không ghi đè kết luận, follow-up cùng key một proposal/link |
| AC55 | Hai người claim cùng yêu cầu tài liệu | Một thắng, một REQUEST_CLAIMED/conflict |
| AC56 | Fulfill bằng nội dung audience hẹp hơn | Từ chối; không lộ tài liệu hoặc giả đã đáp ứng request |
| AC57 | Fulfillment bị ẩn/thu hẹp quyền | API không lộ ngay, reconcile request open/claimed nếu không còn nguồn hợp lệ |
| AC58 | Bài Lessons Learned thiếu nguyên nhân/phòng ngừa | Publish validation, vẫn giữ draft để sửa |
| AC59 | Theo dõi và onboarding lặp, nhắc tên trong code block | Không nhân subscription/notification; code block không tạo mention |
| AC60 | Mention người không có quyền nguồn | Không gửi/lộ title, không nới ACL |
| AC61 | Digest đổi timezone/DST và worker retry | Cửa sổ UTC không chồng, một notification chỉ một batch |
| AC62 | Batch có item đã mất quyền hoặc rỗng | Suppressed, không title/count trái quyền, không bản tin giả |
| AC63 | Share collection có item private và note | Chỉ thấy item/note đủ cả hai ACL; count/paging đúng |
| AC64 | Editor collection đổi ACL hoặc sửa collection mặc định | Từ chối; không tự public bookmark |
| AC65 | Bỏ upvote/đổi accept/hide và event sai thứ tự | Ledger net đúng trạng thái hiện tại, credit/reversal không nhân đôi |
| AC66 | Re-accept cùng answer sau sửa revision | Không thêm +5 lần nữa |
| AC67 | Nguồn từ organization chuyển private | Leaderboard/award public không lộ ngay; ledger reconcile đảo điểm đúng |
| AC68 | Badge sửa rule active, revoke/trao lại | Bản luật mới, giữ lịch sử; không nhân huy hiệu hoặc tự cấp quyền |
| AC69 | Analytics mẫu số 0, draft answer, refresh view | Tỷ lệ null khi không có mẫu; draft không tính; một unique view/ngày |
| AC70 | Search nhiều nguồn, page thứ hai, nguồn có filter không hỗ trợ | Count/page đúng ở DB, không bỏ filter để mở dữ liệu |
| AC71 | Learner xem resource khi chỉ có quyền Learning hoặc chỉ Community | Không đọc nhãn/body/file trái quyền; cần cả hai |
| AC72 | Sửa resource khóa published, clone draft, update nguồn Community | Published khóa không đổi; clone giữ links hợp lệ; pinned revision không tự nhảy |
| AC73 | AI retrieve private/Guild content bằng index cũ | ACL/current pointer trước topK, không lộ đoạn/mô hình answer |
| AC74 | Quyền/source đổi khi model đang generate hoặc cache hit | Discard toàn answer, không chỉ bỏ citation |
| AC75 | Không có nguồn, model bịa URL/citation ID | no_sources hoặc từ chối citation lạ; không tự bịa nguồn nội bộ |
| AC76 | Prompt injection trong JDL/Wiki/code và client history | Dữ liệu không thành chỉ dẫn/tools; quyền không đổi |
| AC77 | Retry AI request, cache mất/restart/turn treo | Không chạy hai model cùng key; metadata rõ resultRetained=false; turn quá hạn failed |
| AC78 | Feedback/source turn của người khác | Từ chối, không prompt/answer/citations riêng tư |
| AC79 | Index model/dimension sai hoặc event publish cũ đến muộn | Failed/reindex đúng model, không trộn vector hoặc revive revision cũ |
| AC80 | Reconcile/reindex job do OM ngoài org hoặc client sửa payload | Từ chối, kiểm scope/dedup/hash; JobView không trả payload private |

## Đối chiếu chức năng, DB và API

| Phạm vi cam kết | Bảng chính | Route/cơ chế |
|---|---|---|
| Kho Q&A tìm trước/hỏi sau | contents,revisions,questions,answers,tags,question_links | C01–C07,C12–C28,C136 |
| Blog, micro-sharing, bài học sự cố | contents,revisions,revision_assets | C08–C19,C93–C94 |
| Guild, hội nhóm, onboarding | guilds,guild_members,user_preferences,subscriptions | C50–C61,C99–C101,C133 |
| Wiki, chuẩn coding, Tech Radar, độ mới | wiki_pages,radar_entries,revisions,approvals,maintenance_reviews | C62–C71,C131–C135 |
| Video sharing và transcript | assets,upload_parts,revision_assets,media_transcripts | C43–C49,C72–C77 |
| Review kiến trúc theo phiên bản | review_proposals,invitations,threads,comments | C78–C86 và comment chung |
| Danh bạ chuyên môn, yêu cầu hỗ trợ/tài liệu | user_expertise,help_requests,knowledge_requests,content_links | C35–C36,C87–C92 |
| Tìm kiếm chung/feed | tags,aliases,contents,search_chunks + module adapters | C97–C98, không mở ACL |
| Follow/mentions/in-app/digest | subscriptions,mentions,outbox,notifications,digest_batches/items | C31–C34,C99–C110 |
| Dấu trang/bộ sưu tập chia sẻ | collections,collection_items,collection_access_grants | C29–C30,C102–C107 |
| Điều phối/phân quyền | moderators,reports,moderation_actions,access_grants | C18,C37–C42,C134 |
| Đóng góp/huy hiệu/thống kê | contribution_events,badges,badge_awards,content_views | C111–C122 |
| Liên kết với đào tạo | learning_links + Learning public policy | C123–C125 |
| AI có nguồn và phản hồi | search_chunks,ai_turns,ai_turn_sources,ai_feedback | C126–C130 + Assistant read tool dưới policy |

## Thứ tự kỹ thuật và giới hạn kiểm chứng

1. Review DDL của **51 bảng**, từ điển cột và dependency Identity/Learning/Knowledge/pgvector. Không tự chạy server, thêm FK/UNIQUE hoặc seed quyền; giữ ddl-auto=validate/Flyway off.
2. Xây public policy/search/media adapter và các use case theo phụ thuộc. Thứ tự code không biến chức năng còn lại thành tùy chọn hoặc giới hạn một nhóm chức năng; tiêu chí giao hàng gồm toàn bộ C01–C138 và AC01–AC80.
3. Cập nhật Swagger request/response schema/examples theo DTO; kiểm OpenAPI sinh thực tế đủ 200/schema, binary 206/HEAD và lỗi thống nhất. Source changes cần build/regression thích hợp; chưa sinh source test khi người dùng chưa yêu cầu.
4. Nghiệm thu bằng dữ liệu/tài khoản được phép, worker crash/retry và ACL race thật; phân biệt kiểm tài liệu với test API. S3/probe/Ollama/pgvector/scheduler/cleanup cần cấu hình vận hành được review; không coi đặc tả là bằng chứng đã chạy prod.

## Danh mục response DTO dùng cho toàn bộ API

Các tên dưới đây là projection **whitelist**, không serialize toàn bảng. `ActorRef={userId,displayName}`, `GuildRef={guildId,name}`, `ContentRef={contentId,kind,revisionId,title,url}`; chỉ resolve ref sau khi kiểm quyền. `V` nghĩa thêm version,createTime,updateTime cho resource có row_version; không dùng V thay contentVersion/revisionVersion. `?` là nullable và trả null, không suy là quyền được cấp. P<T> giữ PageResponse chuẩn; alias C/R/Q là ContentDetail/RevisionDetail/QuestionDetail đã mô tả. Capabilities của Content/Guild/Comment là map boolean theo use case; các projection media/transcript/review/resource dùng danh sách tên thao tác được phép theo OpenAPI. Không trả grants/role nội bộ cho người không có quyền.

| DTO | Các field trả về, ngoài V nếu ghi rõ |
|---|---|
| ContentCard | contentId,kind,contentVersion,title,summary,author:ActorRef,visibility,lifecycleStatus,publishedRevisionId,publishedAt,lastActivityAt,tags:TagView[],score,myVote,capabilities |
| QuestionCard | ContentCard + resolutionStatus,answerCount,acceptedAnswerId?,isDuplicate,duplicateTarget:ContentRef? chỉ khi đọc được |
| AnswerCard | ContentCard + questionId,revisionId,isAccepted,acceptedRevisionId?,acceptedRevisionIsCurrent |
| RevisionCard | revisionId,contentId,revisionNo,revisionVersion,title,revisionStatus,publishedAt?,changeNote?,capabilities |
| AccessView | contentId,contentVersion,visibility,primaryGuildId?,grants[{grantId,userId?,guildId?,permission,expiresAt?,status}],capabilities; grants chỉ A/quản lý ACL |
| TagView | V + tagId,slug,name,description?,status,aliases[],mergedIntoTagId?,usageCount đã lọc ACL |
| CommentView | V + commentId,contentId,author:ActorRef,parentCommentId?,reviewThreadId?,revisionId?,bodyMarkdown?,status,isDeleted,capabilities; tombstone không có body cũ |
| GuildView | V + guildId,slug,name,description?,visibility,joinPolicy,status,owner:ActorRef,effectiveReadOnly,myMembership?,visibleContentCount,visibleMemberCount,capabilities |
| MembershipView | V + membershipId,guildId,user:ActorRef,memberRole,status,joinedAt?,leftAt?,invitedAt?,invitationExpiresAt?,capabilities; không lộ membership ngoài quyền |
| ModeratorView | V + moderatorId,user:ActorRef,orgId,status,grantedBy:ActorRef,grantedAt,revokedAt?; chỉ quản lý moderator được đọc |
| HelpRequestView | V + helpRequestId,questionId,requestedBy:ActorRef,targetUser:ActorRef?,targetGuild:GuildRef?,status,dueAt?,respondedBy:ActorRef?,respondedAt?,capabilities |
| WikiPageCard | ContentCard + guildId,parentPageId?,slug,sortNo,documentClass từ revision published,reviewDueAt?,isOutdated |
| WikiPageView | C + guildId,parentPageId?,slug,sortNo,reviewDueAt?,isOutdated; metadata chuẩn nằm trong revision.context.wiki |
| RadarCard | ContentCard + guildId,technologyName,technologyVersion?,stage,approvalScope,effectiveFrom?,reviewDueAt?; toàn bộ metadata kỹ thuật từ cùng revision đã duyệt |
| ApprovalView | V + approvalId,revisionId,reviewer:ActorRef,decision,note?,decidedAt,contentVersion,revisionVersion |
| MaintenanceReviewView | V + maintenanceReviewId,contentId,revisionId,reviewer:ActorRef,outcome,note?,reviewedAt,nextReviewDueAt?,contentVersion |
| RecordingCard | ContentCard + heldAt,speakers[],durationSeconds,primaryVideoRevisionAssetId,hasTranscript |
| TranscriptView | V + transcriptId,assetId,versionNo,language,bodyText,segments[{startSeconds,endSeconds,text}],status,publishedAt?,capabilities; chỉ trả khi đọc được nguồn |
| ReviewCard | ContentCard + reviewStatus,dueAt?,currentRevisionId,openThreadCount,invitedCount theo ACL |
| ReviewView | C + reviewStatus,dueAt?,concludedRevisionId?,conclusion?,concludedBy:ActorRef?,concludedAt?,openThreadCount,capabilities |
| InvitationView | V + invitationId,proposalContentId,reviewer:ActorRef,invitedBy:ActorRef,status,respondedAt?,expiresAt?,capabilities |
| ThreadView | V + threadId,proposalContentId,revisionId,revisionAssetId?,anchor,status,resolvedBy:ActorRef?,resolvedAt?,resolutionNote?,rootComment:CommentView,capabilities; replies qua C25 lọc reviewThreadId |
| ExpertiseView | expertiseId,user:ActorRef,tag:TagView,description?,availableForHelp; C89 bao ngoài items và preferenceVersion |
| ExpertView | user:ActorRef,expertise:ExpertiseView[],visibleGuilds:GuildRef[],availableForHelp; không email/điện thoại/quyền Identity |
| KnowledgeRequestCard | ContentCard + requestStatus,claimedBy:ActorRef?,dueAt?,fulfilledAt? |
| KnowledgeRequestView | C + requestStatus,claimedBy:ActorRef?,claimedAt?,dueAt?,fulfilledAt?,fulfillments:ContentRef[],capabilities |
| LinkView | linkId,sourceContentId,relationType,targetSourceType,targetId,targetRevisionId?,targetTitle,targetUrl; không trả target mất quyền |
| SearchPageView | items:SearchHit[],page,size,totalElements,totalPages,appliedSourceTypes |
| SearchHit | sourceType,sourceId,revisionId,title,snippet,rank,updatedAt,url,kind?,tags?,answerId?,capabilities; snippet sau ACL và sanitize |
| PreferencesView | V + userId,onboardingCompletedAt?,timeZone,digestFrequency,digestLocalTime,digestWeekday?,nextDigestAt?,notificationPreferences |
| CollectionView | V + collectionId,owner:ActorRef,name,description?,visibility,primaryGuildId?,status,visibleItemCount,isDefault,capabilities; owner mới thấy grants[] |
| CollectionItemView | itemId,collectionId,content:ContentRef,note?,sortNo,collectionVersion; item mất quyền bị loại trước page/count |
| SubscriptionView | V + subscriptionId,userId,targetType,targetId,targetLabel,deliveryMode; không trả label trái quyền |
| NotificationView | notificationId,eventType,content:ContentRef?,actor:ActorRef?,readAt?,deliveredAt?,deliveryMode,message hiển thị được dựng theo ACL hiện tại |
| DigestView | V + batchId,windowStart,windowEnd,status,deliveredAt?,visibleItemCount,unreadCount; C109 thêm items/page/size/totalElements/totalPages, không snapshot private text |
| ReportCard | V + reportId,reasonCode,status,reportedAt,assignedModerator:ActorRef?; không title/body private trước assign |
| ReportView | ReportCard + targetType,targetId,description?,reporter:ActorRef,actions:ActionSummary[],capabilities; thông tin nhạy cảm chỉ reviewer case có quyền |
| EvidenceView | reportId,targetType,targetId,targetVersion,content hoặc comment snapshot tại lần đọc,revisionId?,actions:ActionSummary[]; có audit, không cache public |
| ActionSummary | actionId,actionType,reason,actor:ActorRef,occurredAt; không tự trả previousState/newState nội bộ |
| PartAck | uploadId,assetId,partNumber,sizeBytes,checksumSha256,status,uploadedBytes; không etag/provider/lease |
| UploadSummaryView | uploadId,assetId,originalName,mediaType,status,totalBytes,uploadedBytes,expiresAt,processingErrorCode?,capabilities |
| UploadView | UploadSummaryView + partSize,parts[{partNumber,sizeBytes,checksumSha256,status}],retryAfterSeconds? |
| PinView | guildId,contentId,pinned; chỉ trả khi còn quyền content |
| ContributionView | contributionEventId,orgId,userId,content:ContentRef,reasonCode,pointsDelta,policyVersion,reversesEventId?,occurredAt; C111 bao ngoài ledger PageResponse và eligibleScore |
| ContributorRank | rank,user:ActorRef,eligibleScore,acceptedAnswerCount,receivedUpvoteCount,verifiedMaintenanceCount,badges:BadgeAwardView[] đã lọc nguồn public |
| BadgeView | V + badgeId,code,name,description?,ruleVersion,rule,status |
| BadgeAwardView | awardId,badge:BadgeView,user:ActorRef,status,awardedAt,revokedAt?,revokeReason?; public chỉ active còn đủ điều kiện |
| AnalyticsSummary | period{from,to,timeZone},questions,unanswered,answered,solved,solvedRatio?,firstResponseMedianMs?,activeContributors,uniqueViews,staleContents |
| ActivityPoint | bucketStart,bucketEnd,publishedQuestions,publishedArticles,publishedAnswers,uniqueContributors,uniqueViews; bucket rỗng có giá trị 0 |
| LearningResourceView | learningLinkId,courseRevisionId,lessonId?,content:ContentRef,sortNo,status,capabilities; C124 trả courseRevisionVersion và items[] |
| AIAnswerView | turnId,status,answer?,citations: AISourceView[],resultRetained; không prompt hoặc token nội bộ |
| AISourceView | citationNo,sourceType,sourceId,revisionId,title,url,locator{chunkNo?,startSeconds?,endSeconds?,page?},usedInAnswer; chỉ nguồn còn ACL |
| AIFeedbackView | V + feedbackId,turnId,userId,helpful,reasonCode?,note?; chỉ chủ turn |
| JobView | eventId,status,processedCount,failedCount,errorCode?,availableAt,processedAt?; không raw outbox payload/dedup hash |

Các command trả tập hợp kèm version có shape rõ: C19 `data={contentVersion,items:TagView[]}`, C96 `data={contentVersion,items:LinkView[]}`, C89 `data={preferenceVersion,items:ExpertiseView[]}`, C106 `data={collectionVersion,item:CollectionItemView?}`, C124 `data={courseRevisionVersion,items:LearningResourceView[]}`. C13 RevisionDetail có thêm contentVersion của root. C03/C06/C09 và các create subtype dùng C hoặc Q đầy đủ để FE lấy cả contentVersion lẫn revisionVersion.

Nếu caller A/M chỉ được biết tombstone, C/Q là union `TombstoneView={contentId,contentVersion,isDeleted,isHidden,reasonSafe,capabilities}`, không giữ title/body/asset cũ. Query param C25 có thêm reviewThreadId?; nếu truyền phải resolve đúng proposal rồi lọc thread trước count/page. C95/C96 trả link type liên module nhưng không nới quyền của source/target.
