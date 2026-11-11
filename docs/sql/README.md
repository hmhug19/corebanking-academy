# Khởi tạo Identity / Organization / RBAC

## Chạy local

DB đã triển khai: chạy [`upgrade/2026-11-05-required-final-quiz.sql`](upgrade/2026-11-05-required-final-quiz.sql)
trước backend nhận `finalQuizId`. File chỉ thêm cột nullable theo revision, chạy lại an toàn; không đổi lịch sử
hoàn thành. Rollback cùng tên có hậu tố `.rollback.sql` sẽ từ chối khi còn cấu hình bài thi để tránh mất điều kiện.

Chạy toàn bộ hệ thống trên máy dev, không dùng DB chung (`103.121.90.119`) hay S3 từ xa. Cần Docker, `psql`, Java 21, Maven,
`ffmpeg`/`ffprobe` (video chỉ thành READY sau khi qua kiểm tra bằng `ffprobe`). `.env` hiện có không bị đổi: biến môi trường
nạp từ `.env.local` luôn thắng phần import `.env` của `application.yml`.

```bash
cp .env.local.example .env.local      # thay mọi giá trị change-me; LOCAL_DEMO_PASSWORD lấy từ mục "Đăng nhập demo" bên dưới
docker compose -f compose.yaml -f compose.local.yaml --env-file .env.local up -d db s3 s3-init
scripts/local-db-init.sh                # DB rỗng: baseline + demo, theo thứ tự bên dưới
set -a; . ./.env.local; set +a; mvn spring-boot:run
scripts/local-seed-media.sh             # (terminal khác) khóa DEMO-MEDIA có PDF + video READY
scripts/local-smoke.sh                  # kiểm tra đăng nhập và các API đọc
```

Thứ tự `scripts/local-db-init.sh` chạy, mỗi file một lần với `ON_ERROR_STOP=1`:

| # | File | Ghi chú |
|---|---|---|
| 1 | `learning_hub_identity.sql` | Schema, 7 tài khoản demo, RBAC |
| 2 | `learning_hub_learning.sql` | 30 bảng Learning (gồm `learning_activity_days`, `lesson_notes`, `lesson_bookmarks`, `lesson_feedback`) |
| 2a | `upgrade/2026-11-09-chat-room-games.sql` | Ví token, lịch sử Trên/Dưới, bàn Caro 50×50 và trigger cộng token từ XP mới |
| 3 | `learning_hub_media_progress.sql` | Extension media/tiến độ |
| 4 | `learning_hub_assessment.sql` | Quiz Engine; cần Identity + Learning, nằm trước Knowledge/Community |
| 4a | `upgrade/2026-11-07-flashcards.sql` | Flashcard, trạng thái ôn và đánh dấu theo học viên |
| 5 | `learning_hub_knowledge.sql` | pgvector (image `pgvector/pgvector:pg16`) |
| 5a | `upgrade/2026-11-10-assistant-conversations.sql` | Hội thoại và lượt hỏi-đáp của trợ lý AI; chạy trước backend mới (ddl-auto=validate). Rollback cùng tên `.rollback.sql` xoá hết hội thoại đã lưu |
| 5b | `upgrade/2026-11-10-assistant-pending-actions.sql` | Thay đổi dữ liệu trợ lý đề xuất chờ người dùng xác nhận, lưu trong DB để không mất khi khởi động lại; chạy trước backend mới. Rollback cùng tên `.rollback.sql` bỏ các đề xuất đang chờ |
| 5c | `upgrade/2026-11-10-assistant-feedback.sql` | Đánh giá 👍/👎 của người dùng cho câu trả lời của trợ lý; chạy trước backend mới. Rollback cùng tên `.rollback.sql` bỏ mọi đánh giá |
| 5d | `upgrade/2026-11-11-knowledge-document-scope.sql` | Phạm vi tài liệu kho tri thức (personal, unit, global) để tài liệu cá nhân hoặc của đơn vị không lộ cho người khác; tài liệu cũ thành global; chạy trước backend mới |
| 5e | `upgrade/2026-11-11-course-content-index.sql` | Chỉ mục nội dung khóa học đã xuất bản (đoạn + vector) cho trợ lý AI tìm bài học; cần pgvector và baseline Learning; chạy trước backend mới |
| 5f | `upgrade/2026-11-11-assistant-conversation-lesson-context.sql` | Hội thoại trợ lý gắn với bài học (4 cột context_*); chạy trước backend mới |
| 5g | `upgrade/2026-11-12-course-generation-jobs.sql` | Công việc tạo khóa học bằng AI (jobs và steps); chạy trước backend mới |
| 5h | `upgrade/2026-11-13-course-revision-ai-generated.sql` | Cờ ai_generated trên revision khóa học (nội dung do AI tạo); chạy trước backend mới |
| 5i | `upgrade/2026-11-14-course-revision-review-confirmed.sql` | Người và thời điểm xác nhận đã rà soát khi xuất bản bản do AI tạo; chạy trước backend mới |
| 5j | `upgrade/2026-11-15-course-job-soft-delete.sql` | Đánh dấu job tạo khóa học bằng AI đã xóa thay vì xóa dòng, để hạn mức trong ngày vẫn tính (cột deleted_at). |
| 5k | `upgrade/2026-11-16-assistant-retrieval-trace.sql` | Cột `retrieval_trace` của lượt hỏi đáp (dấu vết truy xuất, chỉ ID và số); chạy trước backend mới |
| 5l | `upgrade/2026-11-16-knowledge-document-course-source-scope.sql` | Phạm vi tài liệu `course_source` cho tệp nguồn của job tạo khóa học; chạy trước backend mới |
| 6 | `learning_hub_community.sql` | 51 bảng Community |
| 7 | `learning_hub_community_functions.sql` | Menu `COMMUNITY`, `COMMUNITY_MODERATION` |
| 8 | `learning_hub_community_catalog.sql` | Tag, alias, luật huy hiệu (org `BIDV110B803`, actor `quantri.ban`) |
| 9 | `learning_hub_learning_demo.sql` | Chỉ dev/test |
| 10 | `learning_hub_assessment_demo.sql` | Chỉ dev/test; cần Learning demo (xem bên dưới) |
| 11 | `learning_hub_learner_quiz_demo.sql` | Chỉ dev/test; cần Assessment demo (xem bên dưới) |
| 12 | `learning_hub_community_demo.sql` | Chỉ dev/test |
| 13 | `learning_hub_community_verify.sql` | Chỉ đọc |

Script từ chối chạy khi `learning_hub.users` đã tồn tại và khi `PGHOST` không phải máy local. Khởi tạo lại từ đầu:
`docker compose -f compose.yaml -f compose.local.yaml --env-file .env.local down -v`, rồi chạy lại các lệnh trên.
Mật khẩu chỉ đọc từ biến môi trường (`DB_PASSWORD`/`PGPASSWORD`, `LOCAL_DEMO_PASSWORD`), không có trong lệnh hay log.
MinIO dùng image `bitnamilegacy/minio` đã đóng băng (image `minio/minio` chính thức không còn pull được) nên chỉ dùng local.
Console MinIO: `http://localhost:9001`.

**Assessment demo** ([learning_hub_assessment_demo.sql](learning_hub_assessment_demo.sql), chỉ dev/test, chạy một lần): 3 nhóm năng lực
`FE-DEMO-CMP-ARCH/OPS/CTRL`, 16 câu hỏi ACTIVE (8 câu mỗi khóa `FE-DEMO-C1`, `FE-DEMO-C2`, SINGLE và MULTIPLE, gắn bài học của khóa), và với
mỗi khóa một đề PRACTICE + một đề FINAL_EXAM đều PUBLISHED, pool là 8 câu. FINAL_EXAM của `FE-DEMO-C2` yêu cầu hoàn thành bài học nên
`hocvien` nhận `blockReason=LESSONS_REQUIRED`. Không có lượt thi nào được tạo sẵn. Lần chạy thứ hai báo lỗi và rollback.

**Learner quiz demo** ([learning_hub_learner_quiz_demo.sql](learning_hub_learner_quiz_demo.sql), chỉ dev/test, chạy một lần): thêm vào
phiên bản đã xuất bản của `FE-DEMO-C1` và `FE-DEMO-C2` một bài học bắt buộc loại quiz, trỏ tới đề FINAL_EXAM của khóa. Sửa trực tiếp
bản đã xuất bản để `hocvien` thấy bài quiz ngay mà không cần nhận lại khóa. Lần chạy thứ hai báo lỗi và rollback.

## Nâng cấp schema (upgrade)

Bộ thẻ cá nhân cần chạy thêm [2026-11-07-personal-flashcards.sql](upgrade/2026-11-07-personal-flashcards.sql) sau script flashcard. Script tạo hai bảng mới, chạy một lần, giữ dữ liệu thẻ đã công bố.

Chạy [2026-11-07-flashcards.sql](upgrade/2026-11-07-flashcards.sql) một lần sau Assessment để tạo bảng flashcard và tiến độ ôn. Script khởi tạo local đã chạy file này. Khi quay về bản ứng dụng cũ, giữ hai bảng để bảo toàn tiến độ và lịch sử công bố đáp án.

`2026-11-06-learning-completion.sql`: dừng BE ghi tiến độ, chạy sau upgrade bài thi cuối ngày 05/11,
rồi triển khai BE/FE mới. Tính lại `course_enrollments.progress_percent` từ các bài bắt buộc theo đúng
revision của từng lần học; không cộng bài thi cuối riêng, không đổi `status`/`completed_at`.
Baseline mới đã có constraint và ý nghĩa mới. Rollback cùng tên tính lại theo công thức cũ từ dữ liệu
hiện tại (không phải phục hồi snapshot), chạy cùng việc quay về BE/FE cũ. Không tự chạy SQL lúc startup.

Script trong `docs/sql/upgrade/` dành cho DB đã chạy baseline. Với DB mới, làm theo thứ tự ở bảng khởi tạo local.

Với DB đang chạy, áp dụng [2026-11-09-chat-room-games.sql](upgrade/2026-11-09-chat-room-games.sql)
sau khi đã có `learning_hub.xp_ledger`, trước khi chạy bản backend có trò chơi phòng học.
Migration thêm bốn bảng và một trigger cho dòng XP dương mới. Giữ các bảng để bảo toàn ví và cược khi đổi bản ứng dụng.
Có thể chạy lại cùng file nếu đã cài schema thử nghiệm trước đó và bảng Caro chưa có ván theo kích thước cũ.
Chạy toàn bộ file với `-v ON_ERROR_STOP=1`, mật khẩu qua `PGPASSWORD`. Xem hướng dẫn rollback của từng upgrade.

| Upgrade | Nội dung | Rollback |
|---|---|---|
| `2026-11-04-program-question-bank.sql` | Thêm `questions.program_id`, cho phép `course_id` NULL; backfill theo revision mới nhất và thêm index lọc chương trình. Câu cũ chưa có chương trình được giữ lại qua đường courseId; chạy trước backend mới, chạy lại an toàn, không đổi chương trình đã được ghi | `2026-11-04-program-question-bank.rollback.sql` từ chối nếu còn câu không gắn khóa hoặc bộ đề dùng câu khác khóa; không tự xóa/gán lại dữ liệu để rollback |
| `2026-11-01-learner-quiz.sql` | Cột `course_lessons.quiz_id` (bài học loại quiz trỏ tới một bài kiểm tra cùng khóa) và index một phần; chạy lại an toàn | `2026-11-01-learner-quiz.rollback.sql` dừng nếu còn bài học loại quiz, ngược lại xóa index và cột |
| `2026-11-02-learning-leaderboard.sql` | Bảng `learning_activity_days` (một dòng mỗi người mỗi ngày học theo `Asia/Ho_Chi_Minh`, phục vụ chuỗi ngày học của bảng xếp hạng) và index; chạy lại an toàn. **Chạy trước khi triển khai backend**: `ddl-auto=validate` từ chối khởi động nếu thiếu bảng | `2026-11-02-learning-leaderboard.rollback.sql` xóa index và bảng, chuỗi ngày học về 0 |
| `2026-11-02-learning-leaderboard-backfill.sql` | Tùy chọn: thêm mỗi người mỗi ngày đã có tiến độ bài học hoặc lượt thi đã chấm (`first_source='backfill'`); bỏ qua ngày đã có nên chạy lại không thêm dòng | `DELETE FROM learning_hub.learning_activity_days WHERE first_source = 'backfill';` |
| `2026-11-07-xp-ledger.sql` | Bảng `xp_ledger` (sổ XP chỉ cộng thêm, trigger chặn sửa/xóa) và `gamification_config_versions`; phiên bản 1 do backend tạo từ `app.learning.points.*` khi dùng lần đầu. **Chạy trước khi triển khai backend**: `ddl-auto=validate` từ chối khởi động nếu thiếu bảng | `2026-11-07-xp-ledger.rollback.sql` xóa cả hai bảng, mọi XP đã ghi mất |
| `2026-11-07-xp-ledger-backfill.sql` | Chuyển lịch sử học vào sổ XP một lần (bài học, đề đạt, thưởng lần đầu, khóa hoàn thành; không chuyển điểm chuỗi). Chạy sau `2026-11-07-xp-ledger.sql`, nên chạy thử trên bản sao và so tổng từng người với điểm cũ trừ phần chuỗi; chạy lại an toàn | Chỉ các dòng `reason = 'migration'`: tắt trigger `trg_xp_ledger_append_only`, `DELETE`, bật lại (lệnh ở đầu file) |
| `2026-11-07-xp-community-backfill.sql` | Chuyển đóng góp cộng đồng đã được chấp nhận hoặc hữu ích vào sổ XP (nguồn `COMMUNITY`, mỗi đóng góp một dòng, tối đa trần ngày mỗi người theo giờ Asia/Ho_Chi_Minh, phiên bản cấu hình 1). Chạy sau `2026-11-07-xp-ledger.sql`; chạy lại an toàn | Chỉ các dòng `COMMUNITY` có `reason = 'migration'`: tắt trigger `trg_xp_ledger_append_only`, `DELETE`, bật lại (lệnh ở đầu file) |
| `2026-11-07-levels.sql` | Bảng `gamification_levels` (bậc thang cấp độ theo phiên bản cấu hình, phiên bản có sẵn nhận bậc thang mặc định 6 cấp) và `learner_levels` (cấp cao nhất đã đạt, chỉ tăng). **Chạy trước khi triển khai backend**: `ddl-auto=validate` từ chối khởi động nếu thiếu bảng | `2026-11-07-levels.rollback.sql` xóa cả hai bảng, cấp đã lưu mất |
| `2026-11-07-badges.sql` | Bảng `badge_definitions` (huy hiệu và luật: 5 loại, ngừng dùng bằng `status`) và `user_badges` (huy hiệu đã nhận, không bao giờ xóa). Bộ huy hiệu khởi đầu do backend tạo khi đọc huy hiệu lần đầu và trao âm thầm cho người đã đủ điều kiện (nguồn `BACKFILL`, không thông báo). **Chạy trước khi triển khai backend** | `2026-11-07-badges.rollback.sql` xóa cả hai bảng, mọi huy hiệu đã nhận mất |
| `2026-11-07-badge-backfill-progress.sql` | `badge_definitions`: thêm `backfill_status` (`RUNNING`/`DONE`/`FAILED`, mặc định `DONE`), `backfill_total`, `backfill_done` để quản trị theo dõi tiến độ trao bù nền khi tạo huy hiệu. **Chạy sau `2026-11-07-badges.sql` và trước khi triển khai backend** | `2026-11-07-badge-backfill-progress.rollback.sql` xóa ba cột |
| `2026-11-07-leaderboard-opt-out.sql` | Bảng `learner_preferences` (học viên ẩn tên khỏi bảng xếp hạng của người khác; mặc định hiện, chỉ có dòng khi đã đổi). **Chạy trước khi triển khai backend** | `2026-11-07-leaderboard-opt-out.rollback.sql` xóa bảng, mọi học viên đã ẩn hiện lại |
| `2026-11-07-gamification-notices.sql` | `learning_reminders`: thêm `kind` (`DEADLINE` mặc định, `BADGE_EARNED`, `LEVEL_UP`), `user_id`, `subject`; `recipient_id` cho phép null. Thông báo đạt huy hiệu và lên cấp nằm trong cùng bảng nhắc học. **Chạy trước khi triển khai backend** | `2026-11-07-gamification-notices.rollback.sql` xóa mọi thông báo huy hiệu/cấp và khôi phục bảng |
| `2026-11-07-working-calendar.sql` | Bảng `working_calendar_days` (ngày nghỉ và ngày làm bù của lịch chung, phục vụ chuỗi ngày học theo ngày làm việc), gieo ngày lễ dương lịch cố định 2026. **Chạy trước khi triển khai backend** | `2026-11-07-working-calendar.rollback.sql` xóa bảng, mọi ngày lịch đã nhập mất |
| `2026-11-03-lesson-notes.sql` | Bảng `lesson_notes` và `lesson_bookmarks` (ghi chú và đánh dấu riêng của học viên trên trang PDF hoặc giây video; xóa mềm, có CHECK nhất quán của neo, không FK, không UNIQUE) cùng hai index một phần; chạy lại an toàn. **Chạy trước khi triển khai backend**: `ddl-auto=validate` từ chối khởi động nếu thiếu bảng | `2026-11-03-lesson-notes.rollback.sql` in số dòng của hai bảng rồi xóa index và bảng, mất mọi ghi chú và đánh dấu |
| `2026-11-04-lesson-feedback.sql` | Bảng `lesson_feedback` (đánh giá sao, nhận xét công khai, câu hỏi giảng viên và báo nội dung của học viên trên một bài học; `status` chỉ có với câu hỏi và báo nội dung; xóa mềm, có CHECK về nội dung, trạng thái và neo, không FK, không UNIQUE) cùng hai index một phần; chạy lại an toàn. **Chạy trước khi triển khai backend**: `ddl-auto=validate` từ chối khởi động nếu thiếu bảng | `2026-11-04-lesson-feedback.rollback.sql` in số dòng của bảng rồi xóa index và bảng, mất mọi phản hồi |
| `2026-11-03-lesson-player-modes.sql` | Hai cột `jsonb` có thể NULL trên `lesson_blocks`: `video_chapters` (mốc) và `transcript` (đoạn transcript) của khối video; không FK, không UNIQUE; chạy lại an toàn. **Chạy trước khi triển khai backend**: `ddl-auto=validate` từ chối khởi động nếu thiếu cột | `2026-11-03-lesson-player-modes.rollback.sql` in số khối đang có mốc hoặc transcript rồi xóa hai cột, mất dữ liệu đó |

```bash
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-01-learner-quiz.sql
# Rollback:
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-01-learner-quiz.rollback.sql
```

```bash
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-02-learning-leaderboard.sql
# Tùy chọn, sau upgrade: tạo chuỗi ngày học từ dữ liệu cũ (cần baseline Assessment).
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-02-learning-leaderboard-backfill.sql
# Rollback:
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-02-learning-leaderboard.rollback.sql
```

```bash
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-03-lesson-notes.sql
# Rollback:
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-03-lesson-notes.rollback.sql
```

```bash
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-03-lesson-player-modes.sql
# Rollback:
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-03-lesson-player-modes.rollback.sql
```

```bash
# Menu "Lĩnh vực và chương trình" (TRAINING_CATALOG) cho ADMIN, SUPER_ADMIN, INSTRUCTOR; chạy lại an toàn.
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-05-training-catalog-function.sql
# Rollback:
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-05-training-catalog-function.rollback.sql
```

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

Backend hiện có thêm 46 API Learning và 30 bảng được định nghĩa tại
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

## Bổ sung dữ liệu tổng hợp Dashboard và báo cáo

[learning_hub_reporting.sql](learning_hub_reporting.sql) tạo `quiz_user_results` (một đề/học viên) và
`attempt_competency_scores` (một lượt thi/nhóm kiến thức), thêm index tra tiếp tục học/lịch sử thi cá nhân,
và khởi tạo hai bảng từ lịch sử `SUBMITTED`/`AUTO_SUBMITTED`. Không tạo điểm mẫu hoặc dòng cho người chưa thi.
Chạy **một lần**, toàn file, sau **Identity → Learning → media/progress → Assessment → reporting**.
Nếu baseline đã chạy thì chỉ chạy file reporting; không chạy lại các baseline.

Tạm dừng thao tác ghi Assessment, gồm job tự nộp, trước khi chạy: script lấy SHARE lock trên ba bảng nguồn
để dữ liệu khởi tạo nhất quán. Lock chờ tối đa 10 giây; không lấy được thì transaction thất bại.
Review/backup trước khi chạy trên môi trường có dữ liệu; không tự chạy file này lúc ứng dụng startup.

```bash
psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/learning_hub_reporting.sql
```

**Trạng thái tích hợp:** backend đồng bộ hai bảng cùng transaction nộp bài/tự nộp/nộp hộ/chấm lại/VOIDED;
Dashboard dùng kết quả theo đề/học viên, báo cáo năng lực dùng tổng điểm theo lượt. Script không cài trigger/job.
Phải chạy extension trước khi dùng bản backend này. Nếu bản backend cũ đã ghi thêm lượt sau khi backfill,
cần tính lại hai bảng từ nguồn trong cửa sổ bảo trì trước khi chuyển sang bản mới; không chạy lại CREATE TABLE.
Ghi trực tiếp bằng SQL không gọi hook backend và có thể làm read model cũ. Contract tại
[LEARNING.md](../api/LEARNING.md#dashboard-reporting) và [ASSESSMENT.md](../api/ASSESSMENT.md#reporting-read-model).

Quy tắc đồng bộ và vận hành:

- Tính lại và upsert kết quả theo đúng khóa chính; retry không cộng dồn. Khi không còn lượt hợp lệ, xóa dòng
  tổng hợp tương ứng. Chấm lại/VOIDED phải cập nhật cả hai bảng, gồm tình huống lượt tốt nhất thay đổi.
- Dùng cùng khóa serialize cho `(quiz_id, user_id)` trước khi tính lại để hai lượt chấm đồng thời không ghi đè
  bằng dữ liệu cũ. Tiến độ video không cập nhật các bảng Assessment này.
- `graded_attempt_count` không phải số lượt đã sử dụng: lượt đang thi cũng chiếm lượt theo chính sách hiện tại.
- BEST lấy best score và `ever_passed`; LAST lấy last score và `last_passed`. Không tính lại đạt bằng điểm đã
  làm tròn hoặc ngưỡng đề hiện tại. Hòa điểm chọn submitted_at mới nhất, tiếp theo attempt_id lớn nhất.
- Radar phải chốt chính sách chọn lượt. Bảng điểm theo năng lực hỗ trợ BEST/LAST hoặc toàn bộ lượt; chọn lượt
  trước khi SUM, không mặc định đổi semantics báo cáo năng lực hiện tại (đang tổng hợp các lượt trong filter).
- `quiz_user_results` là kết quả suốt lịch sử đề/học viên, không thay báo cáo có filter kỳ/đơn vị/lần học.
  Những báo cáo đó lọc `quiz_attempts` đúng phạm vi rồi mới tổng hợp; dùng bảng điểm năng lực theo attempt để
  giảm đọc đáp án. Không gán đơn vị của lượt cuối cho toàn bộ lịch sử.
- Read model không cấp quyền. Scope backend vẫn áp dụng trên tài nguyên/người/lượt thi nguồn; không trả đáp án.
- Chỉ rebuild từ nguồn khi dừng ghi hoặc có cơ chế khóa/snapshot an toàn; rebuild hai bảng nguyên tử, không
  chạy lại toàn bộ script CREATE TABLE trên schema đã tồn tại. Đối soát trước khi chuyển API sang đọc.
- Index mới là ứng viên theo access pattern; cần EXPLAIN (ANALYZE, BUFFERS) với dữ liệu đại diện trước khi
  kết luận hiệu năng. Query tiếp tục học phải khớp partial predicate và vẫn kiểm lần học hiện hành/quyền học.

Không thêm bảng snapshot ngày, điểm thưởng hoặc chuỗi học trong extension này. Nếu transaction chưa COMMIT
thì ROLLBACK hủy toàn bộ thay đổi; sau COMMIT, giữ API đọc nguồn trong lúc xử lý sự cố, không sửa lịch sử thi.

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


## Bộ dữ liệu tổng hợp cô lập: demo-showcase-v1

Hai file mới là script chạy thủ công, không thay thế baseline hoặc các demo FE cũ:

- `learning_hub_showcase_v1.sql`: 1.413 dòng mẫu trong đủ 105 bảng và một marker audit bổ sung.
- `learning_hub_showcase_v1_verify.sql`: đối chiếu manifest, sự tồn tại từng dòng, fingerprint,
  trạng thái tài khoản/hàng đợi và tổng điểm từng lượt thi bằng transaction READ ONLY.

**Trạng thái kiểm chứng:** đã kiểm tra tĩnh tên bảng/cột, cột bắt buộc, độ dài varchar, tham chiếu ID,
10 unique index đơn giản, quyền không hoạt động và 24 lượt thi. Chưa thực thi bằng PostgreSQL hoặc qua API;
môi trường tác giả không có PostgreSQL/Docker, tải môi trường kiểm thử tạm thất bại. Chưa thể coi là script
đã nghiệm thu để chạy trực tiếp trên prod. Phải chạy quy trình kiểm thử dưới đây trên bản sao DB trước.

### Phạm vi và dữ liệu

- Đơn vị riêng `DEMO-SHOWCASE-V1`, một phòng con, một nhóm chuyên môn, một nhóm học tập.
- 12 hồ sơ `DMSH0001`–`DMSH0012`, username `demo.showcase.01`–`demo.showcase.12`;
  email miền `example.invalid`, SSO + locked, không có mật khẩu, không đăng nhập được.
- Role/function mẫu inactive và mọi grant Identity mẫu inactive; moderator Community đã revoked.
  Không chỉnh role, mật khẩu, org/team hoặc grant của người dùng có sẵn.
- 8 khóa published với bài text/process có nội dung đọc, một khóa media nháp, 96 lượt ghi danh chính,
  8 đợt giao học, tiến độ 0/50/100%, thời hạn tương đối, phản hồi/ghi chú/đánh dấu.
- 32 câu hỏi, 8 đề, 24 lượt thi đã chấm, đáp án snapshot và hai bảng tổng hợp kết quả nhất quán.
- 3 Guild, 16 nội dung Community gồm hỏi đáp/bài viết/wiki/radar/review/bài học/yêu cầu tri thức,
  3 bộ sưu tập, bình luận/bình chọn và các ví dụ lịch sử kiểm duyệt/thông báo/đóng góp.
- Hàng đợi không pending/processing, reminder cancelled, notification suppressed, digest failed,
  subscription muted và digest frequency off. Không gửi tin, chạy mô hình AI hoặc upload S3.
- Không có bytes media thực: file failed chỉ ở khóa/bản ghi recording nháp. Transcript là draft.
  Tài liệu Knowledge failed, đoạn vector zero chỉ là sentinel kỹ thuật để phủ bảng và không dùng cho
  tìm kiếm ngữ nghĩa (truy hồi hợp lệ phải lọc document ready). Search chunk Community failed.
  Lượt AI failed, source used_in_answer=false. Đây không phải kết quả xử lý media/AI thật.
- Mọi tên nội dung người dùng thấy có nhãn DEMO; không dùng làm hướng dẫn nghiệp vụ chính thức.

**Phạm vi hiển thị:** bộ mẫu nằm trong đơn vị riêng theo lựa chọn của người yêu cầu. Nó không tự làm đầy
trang học viên, thông báo cá nhân hoặc Community của tài khoản thật đang thuộc đơn vị khác. CommunityPolicy
kiểm tra ownerOrgId bằng orgId của actor. Quản trị GLOBAL có thể kiểm tra các danh sách quản trị theo scope
được backend hỗ trợ; không giả định GLOBAL tự vượt ACL Community. Để duyệt toàn bộ trải nghiệm như người
học/Community, cần cấp tài khoản DEMO có chủ đích qua quy trình quản trị hiện có trong môi trường thử nghiệm;
script này không tự mở khóa, đổi đơn vị hay cấp thêm quyền cho bất kỳ tài khoản nào.

### Kiểm thử trước khi triển khai

1. DBA sao lưu và tạo bản sao DB có cùng schema/extension/quyền với môi trường đích. Không chạy lại baseline
   CREATE TABLE hoặc các file demo cũ trên DB có dữ liệu thật. Cần đủ bảy SQL baseline hiện hành,
   extension media/progress, vector(1024) và các nâng cấp lesson notes/feedback nếu DB dùng baseline cũ.
2. Chạy toàn bộ seed bằng SQL client cùng một connection hoặc psql:

   `psql -X -v ON_ERROR_STOP=1 -d <database-copy> -f docs/sql/learning_hub_showcase_v1.sql`

3. Chạy verify; cần đủ 105 bảng, 1.413 dòng manifest và không có exception:

   `psql -X -v ON_ERROR_STOP=1 -d <database-copy> -f docs/sql/learning_hub_showcase_v1_verify.sql`

4. Chạy seed lần hai và verify lần hai: seed phải NOTICE đã tồn tại, không tăng dòng, không đổi ngày,
   không ghi đè chỉnh sửa. Kiểm tra các API/màn có phạm vi quản trị cho đơn vị DEMO trên bản sao DB.
5. So sánh dữ liệu có sẵn trước/sau, chạy các worker đang được bật trên bản sao DB, xác nhận không phát sinh
   gửi tin, truy cập object store, gọi AI hoặc thay đổi quyền thật. Đối chiếu schema prod với schema đã test.
6. Chỉ sau khi hoàn tất các bước trên mới chạy hai lệnh tương đương trên prod trong đợt triển khai được duyệt.
   Quyền DB cần SELECT/INSERT trên 105 bảng, UPDATE con trỏ courses/community_contents, USAGE sequence và TEMP;
   không cần CREATE/DROP bảng trong schema learning_hub. Không đặt credential trong file hoặc câu lệnh lưu Git.

### Chạy lại, xung đột và hoàn tác

- Một transaction, advisory lock chung riêng bộ seed, lock_timeout 5s, statement_timeout 180s.
  Một lỗi trước COMMIT sẽ rollback toàn bộ dữ liệu; sequence PostgreSQL có thể vẫn tiêu thụ ID khi rollback.
  Không có setval, số ID cố định kiểu bigint, TRUNCATE, DELETE, DROP bảng hoặc UPSERT lên dữ liệu đã tồn tại.
- ID bigint lấy từ INSERT RETURNING. Marker `DEMO_SHOWCASE_INSTALLED` ở audit_log giữ namespace,
  orgId và manifest PK/fingerprint từng dòng. Chạy lại là NO-OP; không bổ sung lại dòng đã bị xóa hoặc đổi ngày.
- Nếu namespace đã tồn tại nhưng thiếu marker, script dừng trước INSERT; không đoán quyền sở hữu dữ liệu.
  Không xóa marker để ép chạy lại. Verify báo thiếu dòng hoặc cảnh báo fingerprint thay đổi để review.
- Không cung cấp DELETE cascade tự động trên prod: quan hệ là logic, không có FK bảo vệ dữ liệu người dùng
  tạo thêm sau seed. Nếu cần gỡ sau COMMIT, DBA phải đối chiếu manifest và mọi tham chiếu mới trong cửa sổ
  bảo trì để lập bản xóa chọn lọc được duyệt. Bản sao DB thử nghiệm có thể hủy/tạo lại theo cơ chế vận hành;
  khôi phục snapshot prod chỉ được thực hiện theo kế hoạch phục hồi đã duyệt, tránh mất giao dịch mới.

