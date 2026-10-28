# Khởi tạo Identity / Organization / RBAC

## Bổ sung Community

[learning_hub_community.sql](learning_hub_community.sql) là baseline thủ công **51 bảng**, chạy một lần sau
Identity → Learning → media/progress → Knowledge (pgvector), trước khi chạy ứng dụng có entity Community.
Giữ `ddl-auto=validate`, Flyway tắt; không tự chạy script lúc startup. Không có FK/REFERENCES/cascade vật lý,
không UNIQUE nghiệp vụ, không seed user/password/moderator. Không chạy lại trên DB đã có bảng Community.
Script dùng cùng schema pgvector của Knowledge (`learning_hub` hoặc schema có trong search_path đã cấu hình).

```bash
psql -h <dev-host> -U <dev-user> -d <dev-database> -v ON_ERROR_STOP=1 -f docs/sql/learning_hub_community.sql
```

DB hiện hữu cần review migration riêng; không DROP dữ liệu để chạy lại baseline. Script được bọc transaction:
lỗi tạo bảng/index thì rollback toàn bộ. Khi đã có dữ liệu, rollback ứng dụng cần kế hoạch phù hợp,
không dùng lệnh xóa schema. Giữ backup/quy trình triển khai của môi trường hiện tại.

Source có 138 route thiết kế Community, hiện bật 135 route và tạm dừng 3 route AI C126–C128; các bảng vẫn giữ nguyên.
Cần cấu hình hạ tầng/actor và nghiệm thu tích hợp trước bật worker trên môi trường thật.
Contract/trạng thái: [COMMUNITY.md](../api/COMMUNITY.md), [cURL](../api/COMMUNITY_CURL.md),
[từ điển cột và sơ đồ](../api/COMMUNITY_DATABASE.md).

### SQL Community: tạo bảng, danh mục và demo FE

| File | Mục đích | Khi chạy |
|---|---|---|
| [learning_hub_community.sql](learning_hub_community.sql) | Tạo 51 bảng/670 cột và 21 index, comment từng cột; không FK/UNIQUE nghiệp vụ | Một lần trên DB chưa có Community, sau các baseline phụ thuộc |
| [learning_hub_community_catalog.sql](learning_hub_community_catalog.sql) | 12 tag, 6 alias, 3 luật huy hiệu; không tạo award/điểm | Khởi tạo danh mục sau khi DBA chọn đúng org/actor và duyệt nội dung |
| [learning_hub_community_demo.sql](learning_hub_community_demo.sql) | Nội dung và quan hệ tổng hợp để FE kiểm thử | **Chỉ dev/test/staging**, chạy một lần sau catalog |
| [learning_hub_community_verify.sql](learning_hub_community_verify.sql) | Chỉ đọc: kiểm 51 bảng/670 cột, 142 tham chiếu logic, pointer revision/accept/thread, XOR và các grain chính; thống kê số dòng | Sau DDL/seed/migration; dữ liệu lớn nên chạy ngoài giờ cao điểm |

Kiểm chứng mới nhất: **235 test pass, 0 skipped**, gồm 35 test Community; đã chạy 49 test từng bị bỏ qua.
DDL gốc đã chạy trên PostgreSQL/pgvector thật. S3 multipart/Range, FFmpeg, transcript, scheduler/digest/ledger/cleanup
và phục hồi lease sau hard crash đã được kiểm thực tế trên hạ tầng local riêng.
Chi tiết và giới hạn tại [Community](../api/COMMUNITY.md#kết-quả-kiểm-thử-tích-hợp-ngày-28102026).
3 API AI vẫn tạm dừng. Chưa kiểm chứng cấu hình AIStor/TLS/network hoặc tải của production; bộ SQL không thay thế
smoke test triển khai và kế hoạch backup/migration của môi trường đích.

**Thứ tự chạy trên DB mới**: Identity → Learning → media/progress → Knowledge → Community → catalog → verify.
Knowledge cài pgvector; runtime DB role cần quyền schema/table/sequence theo tài liệu triển khai.
AI C126–C128 tạm dừng **không bỏ yêu cầu có kiểu vector của schema** vì entity vẫn tồn tại.
Đối với DB đã có Community, không chạy lại baseline; cần migration theo diff schema thực tế và kế hoạch backup/rollback.
Không gộp demo vào baseline hoặc tự chạy các file này lúc ứng dụng khởi động.

Ví dụ psql, dùng cơ chế xác thực của môi trường hiện tại, không đưa mật khẩu vào command:

```bash
# DB mới: các baseline Identity/Learning/media/Knowledge phải được chạy trước.
psql -h <host> -U <db-user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/learning_hub_community.sql
# Sửa v_org_code và v_actor_name ở đầu khối DO của catalog trước khi chạy.
psql -h <host> -U <db-user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/learning_hub_community_catalog.sql
psql -h <host> -U <db-user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/learning_hub_community_verify.sql

# Chỉ dev/test/staging: chọn đúng DB trước khi chạy demo, sau đó kiểm tra lại.
psql -h <test-host> -U <test-user> -d <test-database> -v ON_ERROR_STOP=1 -f docs/sql/learning_hub_community_demo.sql
psql -h <test-host> -U <test-user> -d <test-database> -v ON_ERROR_STOP=1 -f docs/sql/learning_hub_community_verify.sql
```

Catalog mặc định resolve org code `BIDV110B803`, actor username `quantri.ban`; phải thay cho môi trường khác.
Actor cần active, đã đổi mật khẩu và có ADMIN đúng scope, SUPER_ADMIN GLOBAL hoặc moderator hiện hữu của org.
Script không tự cấp quyền. Catalog chạy lại chỉ INSERT mục còn thiếu, giữ nguyên mục đã sửa/retire/merge và ghi audit
chỉ khi thực sự thêm dữ liệu; nếu có tag slug/badge code trùng grain trong org thì dừng để DBA xử lý.
Catalog là gợi ý tag và luật huy hiệu, không phải quyết định thư viện nào được phép sử dụng trong ngân hàng.

Demo dùng ba tài khoản tổng hợp `giangvien`, `hocvien`, `hocvien.tichhop` cùng org `BIDV110B803` đã tồn tại và active,
`must_change_password=false`; không tạo/sửa Identity hoặc moderator. Với Identity khác phải sửa phần resolve và review
script trước. ID lấy bằng `RETURNING`, không hardcode ID số hoặc sửa sequence. UUID/request_hash trong seed dành riêng
cho fixture SQL, không dùng để retry một request tạo nội dung qua API. FE tạo mới phải dùng UUID mới từ client.

Sau demo có:

- 3 Guild, 9 membership: owner/member active, yêu cầu join pending và lời mời private Guild invited.
- 15 nội dung đủ 9 kind, 16 revision: 3 question solved/unanswered/answered, 2 answer, 3 article organization/guild/restricted,
  2 Wiki nháp có cây cha/con, 1 Radar nháp, 1 review mở, 1 bài học sự cố, 1 yêu cầu tài liệu, 1 recording nháp chờ video.
  Một article có đồng thời published và draft để thử cập nhật version.
- Comment/reply, review invitation đã accept và thread có root comment, vote, content link, help request,
  expertise, subscription, preferences, 2 collection và grant chia sẻ, 1 report open.
- 15 event outbox **pending** đúng nguồn published/accept/vote. Chỉ khi bật worker bằng actor được cấu hình thì mới
  phát sinh notifications/index/ledger/award theo logic thật. Không có event giả đã processed hoặc dữ liệu gửi ra ngoài.

`giangvien` xem được bản nháp ở C11 `/me/contents`; Wiki/Radar cần submit và moderator độc lập duyệt qua API để xuất bản.
`hocvien` đang được mời vào DevOps private Guild nên chưa xem bài trong đó; `hocvien.tichhop` là active member và xem được.
Bài restricted được grant read cho `hocvien`; `hocvien.tichhop` không được xem. Collection chia sẻ cho người này chỉ hiện
bài còn quyền, không lộ mục restricted hoặc ghi chú của mục bị lọc. Collection demo không phải bookmark mặc định `Đã lưu`.
Recording không thể phát/publish trước khi upload video thật; API AI vẫn trả 404.

Giữ trống các bảng cần thao tác/hạ tầng thật: asset/part/revision_asset/transcript, search chunk, AI,
moderator/approval/action/maintenance, notification/digest, ledger/award, Learning link, mention, question link và content view.
Điểm tổng hợp đọc có thể đã phản ánh vote/accept; ledger/award chỉ xuất hiện sau worker reconcile hợp lệ.
Không seed số liệu giả để làm UI trông như đã chạy các luồng này.
Nếu tài khoản đã có expertise với `available_for_help=false`, demo giữ lựa chọn cũ và chỉ tạo help request khi người
nhận hiện có ít nhất một expertise đang cho phép trợ giúp.

Chạy **toàn bộ file** bằng SQL client có stop-on-error; transaction bảo đảm không có bộ dữ liệu nửa chừng.
Demo có marker audit và prefix `FE-DEMO`/`fe-demo-`, lần hai báo lỗi và rollback thay vì sửa dữ liệu FE đã thao tác.
Nếu lỗi không xóa schema để chạy lại. Dùng DB test mới hoặc xử lý theo kế hoạch dọn dữ liệu được duyệt.

Kiểm chứng bộ SQL ngày 28/10/2026 trên PostgreSQL 16 local riêng:

- Catalog thêm 21 dòng lần đầu, lần hai thêm 0; actor không có quyền bị từ chối trước khi ghi.
- Demo tạo 15 content/16 revision/3 Guild; chạy lại bị chặn và số dòng không tăng.
- Cố ý gây lỗi ở audit cuối script: toàn bộ content/Guild/outbox của lần seed lỗi rollback về 0; sau đó chạy file chuẩn thành công.
- Script chỉ đọc kiểm tra 51 bảng/670 cột, 142 tham chiếu, pointer và grain đã pass.
- 8 nhóm kiểm tra qua HTTP thật với tài khoản demo đã pass: danh mục, Q&A/accept, draft/revision, quyền restricted/private Guild,
  collection lọc mục không có quyền, các màn hình đọc khác và API AI vẫn 404.

Đợt xác minh bổ sung đã chạy **DDL nguyên bản** trên PostgreSQL 16.15/pgvector 0.8.0, giữ `vector(1024)` và index HNSW
của Knowledge. Catalog/demo/verify nguyên bản cũng pass trên DB này. Không còn dùng bản sao `real[]` làm bằng chứng
cho kiểm chứng vector. Hạ tầng S3/video/worker/crash đã kiểm local; model inference và cấu hình production chưa được chạy.

## Bổ sung module Learning

Backend hiện có thêm 46 API Learning và 26 bảng được định nghĩa tại
[learning_hub_learning.sql](learning_hub_learning.sql). Review và chạy file Learning **một lần sau Identity**
trên môi trường được phép, trước khi chạy bản ứng dụng có entity Learning. File không có FK/REFERENCES,
không seed/reset/cấp quyền và không sửa 8 bảng Identity. Không chạy lại trên schema đã có các bảng Learning;
nếu đã có dữ liệu cần kế hoạch nâng cấp riêng. Giữ ddl-auto=validate và Flyway off.

Contract và hướng dẫn từng bảng: [LEARNING.md](../api/LEARNING.md).
Request mẫu: [cURL](../api/LEARNING_CURL.md), [Postman](../postman/learning.postman_collection.json).

## Bổ sung Quiz Engine (Assessment P0)

[learning_hub_assessment.sql](learning_hub_assessment.sql) tạo 10 bảng ngân hàng câu hỏi, cấu hình bài thi, lượt thi, lịch sử chấm lại,
snapshot đề, đáp án và cấp thêm lượt. Chạy **một lần sau Identity và Learning**, trước khi chạy bản ứng dụng có entity
Assessment (ddl-auto=validate sẽ từ chối khởi động nếu thiếu bảng). Không FK/REFERENCES, không DROP, không seed; nhóm
năng lực tạo qua API. Chưa chạy SQL lên DB server của ứng dụng. Contract: [ASSESSMENT.md](../api/ASSESSMENT.md).
SQL Identity dưới đây vẫn là nguồn khởi tạo duy nhất của **cụm Identity**, không thay bằng SQL Learning.

## Dữ liệu Learning mẫu cho FE

[learning_hub_learning_demo.sql](learning_hub_learning_demo.sql) là script INSERT **tùy chọn**, chạy thủ công
trên database dev/test sau **Identity → Learning → media/progress → Learning demo**. Không tự chạy lúc startup.
Script sử dụng org `BIDV110B803` và ba tài khoản `giangvien`, `hocvien`, `hocvien.tichhop` của bộ Identity demo;
kiểm tra active, đã đổi mật khẩu và grant cần thiết trước khi ghi. Không tạo/sửa tài khoản, password hoặc cấp quyền.
Trên DB dùng Identity khác, cần điều chỉnh phần resolve Identity và review trước khi chạy.

Ví dụ chạy bằng psql, dùng cơ chế kết nối/mật khẩu của môi trường dev hiện tại:

```bash
psql -h <dev-host> -U <dev-user> -d <dev-database> -v ON_ERROR_STOP=1 -f docs/sql/learning_hub_learning_demo.sql
```

Chạy **toàn bộ file một lần**, không chọn riêng từng INSERT vì các ID được nối bằng biến trong khối DO.
Mã có prefix `FE-DEMO-`; ID số do DB sinh, không hardcode ID org/team hoặc chỉnh sequence. Toàn bộ INSERT/UPDATE
trong một transaction; dữ liệu demo đã tồn tại thì báo lỗi trước khi ghi, không upsert/ghi đè dữ liệu FE đã sửa.
Nếu SQL client báo transaction aborted, thực hiện ROLLBACK; không chạy lại baseline hoặc xóa dữ liệu để vượt lỗi.

| Dữ liệu / tài khoản | Kịch bản FE |
|---|---|
| 3 lĩnh vực, 4 chương trình, 6 phân hệ published | Dropdown, bộ lọc và tạo khóa |
| `FE-DEMO-C1` — Tổng quan | Được giao, đang học 50%, hạn sau 2 ngày |
| `FE-DEMO-C2` — Quản lý thay đổi | Được giao, chưa bắt đầu, hạn sau 14 ngày |
| `FE-DEMO-C3` — EOD/COB | Chưa bắt đầu, quá hạn 1 ngày |
| `FE-DEMO-C4` — Hội nhập | Hoàn thành 100% trước hạn |
| `FE-DEMO-C5` — Tiền gửi | Tự chọn, đã tự đăng ký, chưa bắt đầu, không có hạn |
| `FE-DEMO-C6` — An toàn thông tin | Nháp của giảng viên; không xuất hiện ở catalog học viên |
| `giangvien` | Quản lý 6 khóa và nhóm; có thêm LEARNER OWN để thử màn học |
| `hocvien`, `hocvien.tichhop` | Mỗi người có 5 enrollment với các trạng thái trên |

Có 9 chương, 15 bài, 31 khối (text/process/video lỗi), 45 bước quy trình, 1 nhóm/3 thành viên,
4 đợt giao, 12 recipient, 15 enrollment, 12 lesson progress, 24 block progress và 12 lịch nhắc.
Ngày hạn tính tương đối tại lúc chạy, không dịch chuyển mỗi lần mở ứng dụng. GET reminders ban đầu có thông báo
gần hạn/quá hạn; nhắc của khóa hoàn thành đã cancelled. Các SELECT cuối file trả ID thực tế để điền vào Postman/cURL.
Các key `FE-DEMO-ASSIGN-*` dành riêng cho fixture SQL, không dùng làm idempotencyKey khi POST giao học qua API.

**23/26 bảng ban đầu có dữ liệu mẫu.** Ba bảng giữ trống có chủ đích: `course_shares` cần đơn vị nhận khác
đơn vị sở hữu (Identity demo hiện chỉ có một đơn vị), `video_watch_ranges` cần video thật và heartbeat hợp lệ,
`course_assessments` cần Quiz Engine chưa triển khai. Không chèn ID tham chiếu giả để đủ số bảng.
Hai bảng extension `asset_upload_parts`/`learning_user_leases` cũng không seed phiên upload/học đang hoạt động.
Khóa published dùng chữ/quy trình, có thể thử hoàn thành qua API mà không cần S3. Một asset failed và block video
tương ứng chỉ nằm ở khóa nháp để thử UI lỗi upload; không có file thật và không tải/phát được. Muốn thử video,
PDF, ảnh hoặc tệp đính kèm, upload qua API media và chờ worker kiểm tra xong; không đổi metadata giả thành ready.

## Extension media và tiến độ (v2 rút gọn)

[learning_hub_media_progress.sql](learning_hub_media_progress.sql) chạy **sau Identity và Learning**,
không chạy thay baseline. Thêm 2 bảng `asset_upload_parts` (manifest upload) và `learning_user_leases`
(một phiên học được phép ghi cho mỗi tài khoản), cùng metadata media/probe/rendition, index/comment và view.
Không tạo FK, không seed/reset dữ liệu, không DROP TABLE/COLUMN. Bản Design ngoài source đã được đồng bộ.
Thiết kế và 17 tình huống demo: [LEARNING.md](../api/LEARNING.md#my-learning-progress-design).

DB giữ PK/UNIQUE/NOT NULL và 14 CHECK số cơ bản: size/duration/fence/sequence không âm, part/page hợp lệ,
phần trăm 0–100 và khoảng xem có end > start. Lifecycle ready/processing, checksum, giới hạn S3, loại/trạng thái,
tổ hợp metadata/session/event và xóa mềm do backend kiểm trong use case. Các CHECK nghiệp vụ v1/v2 cũ được
**DROP theo tên đã biết**, kể cả CHECK PostgreSQL tự đặt tên; không quét/xóa constraint tùy ý hoặc nới khóa chống trùng.
Kích thước chunk ở DB chỉ cần dương; giới hạn part theo S3 và uploaded_bytes <= total do service kiểm.

Đã kiểm chứng bằng PostgreSQL WASM/PGlite: baseline mới, nâng từ v1, nâng từ v2 cũ và rerun, giữ dữ liệu hiện hữu
(346 assertions). Entity/API upload, session/progress và worker kiểm file đã được nối trong source; xem contract Learning.
Nginx offload cần cấu hình vận hành riêng; Quiz Engine chưa triển khai. Chưa chạy SQL lên DB server của ứng dụng.
Validation lifecycle/hash/session do service xử lý; khóa transaction/PK/UNIQUE vẫn cần để chống ghi đồng thời.

Trước chạy DB thật: backup, review dữ liệu trùng grain, giới hạn số, status casing và mọi khác biệt schema tự chỉnh.
Preflight casing chỉ chạy lúc thực thi script; không kiểm theo mỗi request, không sửa dữ liệu. CHECK/UNIQUE còn giữ
sẽ từ chối dữ liệu không hợp lệ. IF NOT EXISTS không hòa giải mọi định nghĩa cột/index cũ.
Script thay CHECK số có tên và partial index cleanup để sửa casing v1, có thể cần khóa/revalidate dữ liệu;
lock_timeout 5 giây/statement_timeout 120 giây. Có lỗi thì ROLLBACK, không chạy lại baseline hoặc xóa dữ liệu để vượt kiểm tra.
DB đã chạy v1/v2 có thể chạy toàn bộ bản rút gọn sau review; không chỉ copy phần CREATE TABLE vì sẽ còn CHECK cũ.

Cột storage_provider/lease per block của v1 được giữ nhưng deprecated; không tự xóa/copy lại dữ liệu.
Khi triển khai BE, map schema, đối soát primary asset/metadata đã kiểm chứng và chuyển mọi instance/đường ghi
sang user lease cùng kế hoạch; không fallback lease cũ hoặc để bản BE cũ ghi song song. Giữ Flyway off, không tự chạy SQL startup.

## Bổ sung kho tri thức "Trợ lý AI"

[learning_hub_knowledge.sql](learning_hub_knowledge.sql) tạo `knowledge_documents` (đồng thời là hàng đợi xử lý nền)
và `knowledge_document_chunks` (đoạn văn bản kèm vector `vector(1024)` của bge-m3, chỉ mục HNSW cosine). Cần
PostgreSQL có extension pgvector (local: image `pgvector/pgvector:pg16` trong `compose.yaml`); file tự chạy
`CREATE EXTENSION IF NOT EXISTS vector` nên tài khoản chạy script cần quyền tạo extension. Thứ tự chạy trên database
mới: Identity → Learning → Knowledge, mỗi file một lần. Không FK/REFERENCES, không DROP, không seed; giữ
ddl-auto=validate và Flyway off. Chức năng `AI_ASSISTANT` nằm trong file Identity.
Contract: [KNOWLEDGE.md](../api/KNOWLEDGE.md).

## Identity / Organization / RBAC

Nguồn SQL duy nhất: [learning_hub_identity.sql](learning_hub_identity.sql), PostgreSQL 14+. Chạy toàn bộ file một lần trong database/schema mới hoặc vừa được tạo lại. Không cần script upgrade, gán root, extension pgcrypto, tham số bind hoặc Flyway. File không tạo/xóa database và không tự DROP dữ liệu; không chạy đè database đang có dữ liệu cần giữ.

## Nội dung

- 8 bảng: orgs, teams, users, roles, functions, role_functions, user_roles, audit_log; kèm comment cho từng bảng/cột và 12 index.
- 5 vai trò: SUPER_ADMIN, ADMIN, LEARNER, INSTRUCTOR, REPORT_VIEWER; 17 chức năng (gồm AI_ASSISTANT, route /assistant, cấp cho mọi vai trò) và mapping màn hình hiện có. Danh mục màn hình không có nghĩa backend đã triển khai API khóa học/thi/báo cáo.
- Một tổ chức gốc trong phạm vi demo: BIDV110B803 — Ban Quản lý và phát triển Core Banking (UNIT). 15 nhóm/bộ phận giữ nguyên mã và tên từ dữ liệu nguồn; gồm Ban Giám đốc, BA&QC, phát triển Core/Kênh, tích hợp, quản trị và hỗ trợ. Không tạo phòng trung gian hoặc node cha ngoài phạm vi; HO/WORK_LOGIC là loại nguồn, không thay trực tiếp org_type nội bộ.
- 7 tài khoản LOCAL, 8 phân quyền theo đúng cặp role/scope và một audit khởi tạo. ID số tự tăng được database sinh; dữ liệu tham chiếu bằng mã, không cần chỉnh sequence.
- Không có FOREIGN KEY vật lý. Service kiểm tra quan hệ logic, cây active, org/team và scope. users.must_change_password đã nằm trong schema; không cần nâng cột sau tạo.

## Đăng nhập demo

Mật khẩu công khai của bộ dữ liệu demo: **Demo@123**. Có thể đăng nhập bằng mã cán bộ hoặc username dưới đây; tất cả tài khoản seed có must_change_password=false, không bắt buộc đổi mật khẩu lần đầu. Mật khẩu mới từ 6 ký tự và tối đa 72 byte UTF-8. Đây là dữ liệu tổng hợp được yêu cầu cho demo, không dùng tài khoản hoặc mật khẩu thật.

| Mã cán bộ | Username | Vai trò / phạm vi | Tổ chức / nhóm |
|---|---|---|---|
| 000001 | super.admin | SUPER_ADMIN / GLOBAL | Ban Core Banking / chưa gán nhóm |
| 000002 | quantri | ADMIN / GLOBAL | Ban Core Banking / chưa gán nhóm |
| 000003 | quantri.ban | ADMIN / ORG BIDV110B803 | Ban Core Banking / chưa gán nhóm |
| 000004 | hocvien | LEARNER / OWN | Nhóm BA&QC Tiền gửi — BIDV110B803103 |
| 000005 | giangvien | INSTRUCTOR / ORG BIDV110B803 và LEARNER / OWN | Nhóm phát triển Core Banking — BIDV110B8031281 |
| 000006 | baocao | REPORT_VIEWER / TEAM BIDV110B8031000 | Ban Giám đốc — BIDV110B8031000 |
| 000007 | hocvien.tichhop | LEARNER / OWN | Nhóm Tích hợp Core Banking — BIDV110B8031280 |

1. Chạy script bằng SQL client trên database mới, với quyền tạo schema, bảng và index.
2. Cấu hình DB_URL/DB_USER/DB_PASSWORD, JWT_SECRET trong .env hiện tại. Giữ ddl-auto=validate và Flyway off. Nếu runtime DB user khác chủ schema, DBA cấp quyền schema/bảng/sequence theo hướng dẫn triển khai.
3. Để trống ba biến APP_BOOTSTRAP_ADMIN_ID/USER/PASSWORD vì script đã tạo root. Bootstrap Java vẫn giữ cho trường hợp khởi tạo có chủ đích; khi có root active thì không tạo thêm hoặc reset quyền/mật khẩu.
4. Đăng nhập super.admin hoặc 000001 bằng mật khẩu demo. /auth/me trả mustChangePassword=false và quyền SUPER_ADMIN; có thể dùng ngay các API quản trị. Tài khoản LOCAL tạo mới qua API hoặc root tạo qua bootstrap vẫn phải đổi mật khẩu tạm như logic hiện tại.
5. quantri.ban quản lý toàn Ban gồm 15 nhóm và các tài khoản thường trong Ban; hocvien.tichhop là học viên nhóm tích hợp, không phải user ngoài đơn vị. Quyền TEAM của baocao chỉ gắn với Ban Giám đốc; tên bộ phận/chức danh không tự cấp quyền quản trị. Chỉ SUPER_ADMIN quản lý tài khoản đang mang vai trò quản trị.

Schema/seed cùng một transaction PostgreSQL; có lỗi thì rollback transaction trước khi chạy lại. Database đã có dữ liệu cần giữ phải có kế hoạch thay đổi riêng, không dùng file khởi tạo để nâng cấp hoặc reset. Không có script nào tự chạy khi ứng dụng khởi động.
