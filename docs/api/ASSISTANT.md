# Trợ lý nhanh (agent có công cụ)

Một chatbot nhỏ trong app, tách khỏi màn hình "Trợ lý AI" (`/assistant`). Backend chạy vòng lặp agent với `qwen3:8b`
trên Ollama cục bộ: mô hình có thể gọi công cụ để tìm tài liệu, mở màn hình của chính người dùng hoặc đề xuất sửa hồ sơ
cá nhân. Công cụ chỉ **đọc** chạy ngay; công cụ **thay đổi dữ liệu** không bao giờ chạy trong vòng lặp: backend giữ
thành một yêu cầu chờ và chỉ chạy khi người dùng xác nhận bằng một request riêng, dưới quyền của chính họ.

- Request mẫu: [Postman](../postman/assistant.postman_collection.json). Swagger: `/swagger-ui.html`.
- Mọi API dưới `/api/v1/assistant/agent`, yêu cầu JWT hợp lệ và chức năng `AI_ASSISTANT` (seed cấp cho mọi vai trò;
  tài khoản còn phải đổi mật khẩu tạm không có chức năng nào), thiếu thì `CORE-ACCESS_DENIED` (HTTP 403). Trả `BaseResponse` (xem
  [API_CONTRACT.md](API_CONTRACT.md)) trừ luồng NDJSON của `/stream`.
- Chỉ gọi Ollama cục bộ; không gửi dữ liệu ra ngoài máy chủ. Nhật ký chỉ ghi tên công cụ, số lượng và thời gian,
  không ghi câu hỏi, câu trả lời hay giá trị hồ sơ.

## Cấu hình (`application.yml`, biến môi trường ghi đè)

| Khóa                                   | Mặc định | Ý nghĩa                                                          |
| -------------------------------------- | -------- | ---------------------------------------------------------------- |
| `app.assistant.agent.max-steps`        | `4`      | Số lần gọi mô hình tối đa cho một lượt (`ASSISTANT_AGENT_MAX_STEPS`) |
| `app.assistant.agent.history-messages` | `20`     | Số tin nhắn lịch sử tối đa nạp từ hội thoại (10 lượt)            |
| `app.assistant.history.retention` | `90d` (`ASSISTANT_HISTORY_RETENTION`) | Hội thoại không hoạt động quá thời gian này bị xoá hẳn mỗi đêm 03:30 |
| `app.assistant.agent.action-ttl`       | `10m`    | Thời hạn của một yêu cầu chờ xác nhận (`ASSISTANT_ACTION_TTL`)   |
| `app.assistant.agent.max-concurrent-turns` | `4`  | Số lượt chạy cùng lúc trên server (`ASSISTANT_MAX_CONCURRENT_TURNS`) |
| `spring.mvc.async.request-timeout`     | `15m`    | Thời gian tối đa của một stream (`ASSISTANT_STREAM_TIMEOUT`); phải ≥ `max-steps` × `read-timeout` của Ollama |

Mô hình, URL và timeout dùng chung cấu hình `app.knowledge.ollama.*` (xem [KNOWLEDGE.md](KNOWLEDGE.md)). Mỗi lần gọi
mô hình dùng `think: false`, `temperature 0.1`, `num_ctx 8192`.

## POST /api/v1/assistant/agent/stream

Một lượt hội thoại. Trả `application/x-ndjson`, mỗi dòng một sự kiện JSON, không bọc `BaseResponse`, có header
`X-Accel-Buffering: no`. Client nên gửi `Accept: application/x-ndjson, application/json` để lời từ chối trước khi stream
(envelope JSON) không bị trả 406.

```json
{ "conversationId": 42, "message": "Mở trang quản lý người dùng" }
```

- `message` 1–500 ký tự sau khi bỏ khoảng trắng hai đầu, nếu không là `CORE-VALIDATION_FAILED`.
- `context` (tùy chọn): `{ courseId, revisionId, lessonId }` của bài học đang xem (nút "Hỏi trợ lý AI" trên trang bài
  học). Server kiểm quyền đọc đúng bài đó (quản lý khóa hoặc ghi danh còn hiệu lực trên revision đang xuất bản); bài không
  tồn tại, chưa xuất bản hoặc không đọc được đều là envelope `ASSISTANT-LESSON_NOT_FOUND` trước khi stream bắt đầu. Có
  `context` thì server không gọi mô hình phân loại (dùng `qa_tai_lieu` nếu không nêu `skill`), nói với mô hình tên khóa và
  bài đang mở, và `search_courses` đưa các đoạn của bài đó lên đầu kể cả khi độ tương đồng thấp. Hội thoại mới lưu bài này
  (`context_*`), chi tiết và danh sách hội thoại trả `context: { courseId, revisionId, lessonId, label }` (bỏ khi không có)
  để mở lại vẫn hiện nhãn "Khóa › Bài"; hội thoại cũ giữ bối cảnh lúc bắt đầu.
- `skill` (tùy chọn): `qa_tai_lieu`, `dieu_huong`, `ho_so`, `tom_tat_bai` hoặc `tro_chuyen`. `tom_tat_bai` (công cụ
  `read_lesson`, không tham số, đọc tối đa 8 đoạn đầu của bài đang mở theo thứ tự) chỉ được dùng khi có `context`; không có
  thì bị bỏ qua như giá trị lạ và không được mô hình phân loại chọn. Có `skill` hợp lệ thì server dùng luôn,
  không gọi mô hình phân loại. Bỏ trống, giá trị lạ hoặc không được phép thì một lần gọi mô hình ngắn chọn skill; phân
  loại lỗi hay trả nhãn lạ thì dùng `qa_tai_lieu`. Mỗi lượt chỉ được cấp công cụ của skill đó (sau khi đọc tài liệu vẫn
  bỏ công cụ tác động như trước).
- `conversationId` bỏ trống để bắt đầu hội thoại mới; sự kiện `conversation` đầu tiên của stream trả mã đó. Mã không
  phải của người gọi (hoặc không tồn tại) là envelope `ASSISTANT-CONVERSATION_NOT_FOUND` trước khi stream bắt đầu.
- Client **không gửi lịch sử**. Backend nạp các lượt `done` gần nhất của hội thoại sao cho cùng câu hỏi mới không quá
  16 000 ký tự (cả lượt hoặc không, câu trả lời dài hơn 4000 ký tự bị cắt), không tóm tắt. Backend không nhận lời gọi
  công cụ hay kết quả công cụ từ client.
- Mỗi lượt được lưu vào hội thoại khi kết thúc (xong, lỗi hoặc bị dừng), kèm câu hỏi, câu trả lời, trích dẫn, tên công
  cụ, màn hình đã mở, mã yêu cầu thay đổi dữ liệu và thời gian. Log không bao giờ chứa văn bản câu hỏi hay câu trả lời.

### Sự kiện

| type              | Trường                          | Ý nghĩa                                                                                  |
| ----------------- | ------------------------------- | ---------------------------------------------------------------------------------------- |
| `conversation`    | `conversationId`                | Dòng đầu của mọi stream: hội thoại lưu lượt này (mới nếu request không nêu mã)           |
| `tool`            | `name`                          | Một công cụ bắt đầu chạy (app hiện dòng trạng thái)                                      |
| `delta`           | `text`                          | Một đoạn văn bản của câu trả lời, nhiều dòng                                             |
| `navigate`        | `path`, `title`                 | App chuyển trang (từ `open_screen`); lượt kết thúc ngay sau đó                           |
| `action_required` | `action` (`PendingActionView`)  | Một thay đổi dữ liệu chờ xác nhận; lượt kết thúc ngay sau đó                             |
| `done`            | `answer`, `citations`, `turnId`, `suggestions` | Dòng cuối khi thành công; `answer` là văn bản cuối cùng (client thay văn bản đã nhận bằng nó); `turnId` là lượt đã lưu, dùng để gửi đánh giá (bỏ khi không lưu được lượt); `suggestions` (tối đa 3 câu, mỗi câu tối đa 120 ký tự) là gợi ý hỏi tiếp, bỏ khi không có |
| `error`           | `messageCode`, `message`        | Dòng cuối khi lỗi giữa chừng                                                             |

`citations` có dạng `CitationView` của chat tài liệu (`n`, `documentId`, `fileName`, `page`, `lineStart`, `lineEnd`,
`snippet`, `sectionPath`, `chunkId` của đoạn tài liệu) và rỗng trừ khi `search_documents` hoặc `search_courses` đã chạy trong lượt. Nguồn là bài học thì thêm
`lesson` (`courseId`, `courseTitle`, `revisionId`, `chapterTitle`, `lessonId`, `lessonTitle`, `blockId`, `url`) và
`documentId` = 0; `url` là `/lesson/{courseId}/{lessonId}`. Citation của tài liệu không có trường `lesson`.

`PendingActionView`:

```json
{
  "id": "6f1c0a52-…",
  "tool": "update_profile",
  "title": "Cập nhật hồ sơ cá nhân",
  "changes": [{ "field": "positionName", "label": "Chức danh", "from": "Chuyên viên", "to": "Chuyên viên chính" }],
  "expiresAt": "2026-10-24T09:10:00Z"
}
```

`from`/`to` là `null` khi trường trống. Không có gì thay đổi cho tới khi `confirm`.

### Vòng lặp và giới hạn

1. Backend dựng ngữ cảnh người dùng (quyền, hồ sơ) **trước** khi stream bắt đầu; công cụ chỉ đọc danh tính từ đó.
2. Mỗi người dùng chỉ có một lượt chạy tại một thời điểm, và server chạy tối đa `max-concurrent-turns` lượt; quá thì
   stream chỉ có một sự kiện `error` `ASSISTANT-BUSY`, không gọi mô hình. Mỗi người dùng được hỏi tối đa
   `app.assistant.limits.questions-per-hour` câu (mặc định 60, biến `ASSISTANT_QUESTIONS_PER_HOUR`) trong 1 giờ trượt;
   câu vượt hạn mức nhận sự kiện `error` `ASSISTANT-RATE_LIMITED` kèm thời gian chờ. Hạn mức và lượt chạy dùng chung
   với hỏi đáp tài liệu (`/api/v1/knowledge/chat`).
3. Hội thoại trong request quá 16 000 ký tự thì backend bỏ các tin nhắn cũ nhất (bắt đầu lại từ một tin nhắn `user`) cho
   tới khi vừa. Gọi mô hình với các công cụ mà người dùng được dùng; một bước chỉ chạy 3 lời gọi công cụ đầu tiên. Nếu
   mô hình gọi công cụ: kết quả công cụ đọc được đưa lại cho mô hình và vòng lặp tiếp tục (tối đa `max-steps` lần gọi).
   Công cụ không tồn tại hoặc tham số sai được trả lại cho mô hình dưới dạng văn bản.
4. Văn bản tài liệu do người khác viết, nên có thể chứa chỉ dẫn cài sẵn. Từ khi `search_documents` đã chạy trong lượt,
   các công cụ hành động thay người dùng (`open_screen`, `update_profile`) không còn được đưa cho mô hình; nếu mô hình vẫn
   gọi (kể cả trong cùng bước với lần tìm), công cụ không chạy và mô hình nhận lại "Không thể mở màn hình hay đề xuất
   thay đổi trong lượt đã đọc tài liệu. Hãy trả lời câu hỏi, người dùng có thể gửi yêu cầu đó riêng.". Kết quả tìm
   kiếm cũng mở đầu bằng một câu nhắc rằng các đoạn chỉ là dữ liệu.
5. `navigate` và `action_required` là kết thúc: backend ghi sự kiện, rồi `done` với một câu cố định, không gọi mô hình
   thêm.
6. Quá `max-steps` mà mô hình vẫn gọi công cụ: `done` với "Mình chưa xử lý được yêu cầu này. Bạn thử diễn đạt lại ngắn
   gọn hơn nhé."
7. Câu trả lời cuối rỗng (sau khi áp quy tắc của `search_documents`): `done` với "Mình chưa có câu trả lời cho yêu cầu
   này. Bạn thử hỏi lại nhé." và `citations = []`.
8. Ollama lỗi giữa chừng, kể cả khi đóng kết nối trước dòng `done`: `error` `ASSISTANT-AI_UNAVAILABLE`. Lỗi khác: `error` `CORE-INTERNAL_ERROR` với thông báo chung.

Từ chối **trước** khi stream bắt đầu là envelope JSON `status: ERROR` (HTTP 200): `CORE-VALIDATION_FAILED` (request
không hợp lệ), `ASSISTANT-AI_UNAVAILABLE` (Ollama không trả lời trong 2 giây); 401 khi thiếu token.

## POST /api/v1/assistant/agent/actions/{actionId}/confirm

Chạy yêu cầu chờ xác nhận bằng quyền của chính người gọi, qua cùng service với màn hình tương ứng. Trả
`BaseResponse<ActionResultView>`:

```json
{ "actionId": "6f1c0a52-…", "tool": "update_profile", "message": "Đã cập nhật hồ sơ.", "data": { } }
```

`data` phụ thuộc công cụ. Mỗi yêu cầu chỉ chạy một lần: yêu cầu được đánh dấu đã xác nhận bằng một câu lệnh UPDATE có điều kiện trước khi chạy, nên bấm hai lần (kể cả
từ hai instance) chỉ chạy một lần. Lời từ chối của service (validation, SSO, đang phải đổi mật khẩu) giữ nguyên envelope của nó.

## POST /api/v1/assistant/agent/actions/{actionId}/cancel

Bỏ yêu cầu mà không chạy. Trả `BaseResponse<Void>`.

## Giải thích một câu của lượt thi

`POST /api/v1/assistant/quiz-attempts/{attemptId}/questions/{displayOrder}/explain` (không body) trả `application/x-ndjson` với
cùng sự kiện như trợ lý nhanh: `conversation`, các `delta`, rồi `done` (`answer`, `citations`, `turnId`) hoặc `error`.
`displayOrder` là thứ tự câu trong `review` của kết quả lượt thi.

Điều kiện nằm ở `QuizAttemptService.explainSource`, dùng đúng quy tắc quyết định kết quả có `review` hay không, nên giải
thích không bao giờ lộ nhiều hơn màn hình xem lại: lượt của chính người gọi (lượt người khác: `ASSESSMENT-NOT_FOUND`), đã
nộp (đang làm: `ASSESSMENT-ATTEMPT_IN_PROGRESS`) và `reviewPolicy` đang cho xem đáp án (`AFTER_SUBMIT`; `AFTER_PASS` khi
đã từng đạt; `AFTER_CLOSE` khi đề đã đóng; `NEVER` không bao giờ). Không được xem: `ASSESSMENT-REVIEW_NOT_AVAILABLE`.
Các từ chối này là envelope JSON trước khi stream bắt đầu. Server kiểm lại ở mỗi lần gọi, không tin việc client đã ẩn nút.

Mô hình nhận câu hỏi, các phương án (đánh dấu đáp án đúng và lựa chọn của người học), giải thích của người ra đề và, khi
câu hỏi gắn bài học, các đoạn của bài đó mà người gọi đọc được (tối đa 8 đoạn đầu), tất cả trong khối "DỮ LIỆU" chỉ để
tham khảo; lời nhắc nằm ở `assistant/explain.md` và nằm trong `promptVersion`. `citations` chỉ có khi câu trả lời ghi
`[n]` trỏ đoạn bài học; câu không gắn bài học vẫn được giải thích từ `explanation` và không có nguồn. Mỗi lần gọi tính là
một câu hỏi trong giới hạn mỗi giờ (`ASSISTANT-RATE_LIMITED` / `ASSISTANT-BUSY` là sự kiện `error` đầu tiên) và được lưu
thành một hội thoại mới tên "Giải thích câu N: …", có thể đánh giá bằng `turnId`.

## Công cụ

| Công cụ            | Loại                         | Ai được dùng                                  | Kết quả                       |
| ------------------ | ---------------------------- | --------------------------------------------- | ----------------------------- |
| `search_documents` | đọc                          | mọi người dùng                                | văn bản cho mô hình + citations |
| `read_lesson`      | đọc                          | chỉ khi có `context` bài học                  | các đoạn của bài đang mở + citations |
| `search_courses`   | đọc                          | mọi người dùng (kết quả theo quyền học/quản lý) | văn bản cho mô hình + citations |
| `open_screen`      | đọc, kết thúc lượt           | người dùng có ít nhất một màn hình mở được    | sự kiện `navigate`            |
| `update_profile`   | đổi dữ liệu, kết thúc lượt   | tài khoản `LOCAL` không phải đổi mật khẩu tạm | sự kiện `action_required`     |

Mọi tham số do mô hình gửi đều được backend kiểm tra lại; Ollama không ép `enum`. Danh tính và quyền chỉ lấy từ ngữ
cảnh dựng trước khi stream, không bao giờ từ văn bản của mô hình.

### `search_documents`

Tham số `query` (1–300 ký tự). Dùng cùng bước tìm kiếm với chat tài liệu (nhúng, top-k, ngưỡng, bỏ đoạn trùng) và trả
cho mô hình các đoạn đánh số `[n]` theo định dạng của chat tài liệu. Số `[n]` tiếp tục tăng qua nhiều lần tìm trong một
lượt; đoạn đã gặp giữ số cũ và không lặp lại. Kết thúc lượt theo quy tắc của chat tài liệu:

- không tìm được đoạn nào trong cả lượt, hoặc mô hình trả "Không tìm thấy thông tin này trong tài liệu.":
  `done.answer` là câu cố định, `citations = []`, dù mô hình viết gì khác;
- câu trả lời không có `[n]` hợp lệ mà câu đầu tiên nói bằng lời khác rằng tài liệu không có: như trên;
- câu trả lời không có `[n]` hợp lệ khác: giữ nguyên `answer`, `citations = []` (khác chat tài liệu, nơi câu không có `[n]`
  trả mọi đoạn);
- ngược lại `citations` là các đoạn của lượt mà `answer` ghi `[n]`, theo thứ tự số, dựng từ metadata của đoạn; `[n]` ngoài
  khoảng bị bỏ qua.

Ollama không phản hồi khi nhúng câu hỏi: sự kiện `error` `ASSISTANT-AI_UNAVAILABLE`.

### `search_courses`

Tham số `query` (1–300 ký tự). Tìm trong chỉ mục nội dung bài học (`learning_hub.course_content_chunks`). Người quản lý
(cùng phạm vi `LearningAccessPolicy.searchScope`) chỉ thấy revision đang xuất bản; trong lúc revision mới chưa lập chỉ mục
xong thì revision đã lập chỉ mục mới nhất vẫn trả lời. Người học thấy đúng revision của ghi danh còn hiệu lực (không bao giờ
thấy chữ của revision mới hơn), kể cả khi khóa đã xuất bản bản mới; lọc nằm trong cùng câu SQL với ORDER BY/LIMIT nên đoạn không đọc được
không chiếm chỗ top-k. Số `[n]` dùng chung với `search_documents` trong lượt; kết thúc lượt theo cùng quy tắc.

Chỉ mục do worker `CourseContentIndexer` dựng (thăm dò mỗi `app.learning.course-index.poll-interval`, mặc định 30s,
chỉ chạy khi Ollama sẵn sàng): mỗi revision đã xuất bản được ghi cùng lúc đoạn và dấu hoàn tất trong một transaction
nên chạy lại hay khởi động lại giữa chừng không nhân đôi. Chỉ mục giữ revision đang xuất bản và mọi revision còn ghi danh
(`status <> 'withdrawn'`); lập chỉ mục revision mới xong mới xóa revision cũ không còn ai học, và không xóa gì khi revision
đang xuất bản chưa lập chỉ mục được. Khối `pdf` và `attachment` (PDF, DOCX, MD) được đọc bằng extractor của kho tri thức,
giới hạn và kiểm tra chất lượng giống tài liệu tải lên; đoạn giữ số trang (`page_number`) và trích dẫn ghi trang. Tệp scan,
phông chữ lỗi, quá giới hạn hoặc định dạng khác (ảnh, PPTX, XLSX) không được lập chỉ mục nhưng không làm hỏng revision.
Revision lỗi (Ollama chạy nhưng nhúng hoặc đọc kho tệp lỗi) được thử lại sau `app.learning.course-index.retry-delay` (gấp đôi
mỗi lần) tối đa `max-attempts` lần rồi chuyển `failed`. Quản trị hệ thống xem `GET /api/v1/admin/learning/course-index/failures`
và thử lại bằng `POST /api/v1/admin/learning/course-index/{revisionId}/retry`. SQL:
`docs/sql/upgrade/2026-11-11-course-content-index.sql`, `docs/sql/upgrade/2026-11-18-course-index-coverage.sql`.

**Bộ tách đoạn v2 và lập chỉ mục lại** (`app.knowledge.chunk.version` = 2). Một bộ tách đoạn dùng chung cho tài liệu, khóa
học và tệp của khối: cắt theo mục (đề mục) trước, rồi theo kích thước (mục tiêu 400, tối đa 550 token ước lượng), giữ phần
gối đầu qua ngắt trang, không cắt giữa bảng hoặc khối mã trừ khi riêng nó vượt mức tối đa (khi đó mỗi phần lặp lại dòng
tiêu đề bảng). Văn bản nhúng và đưa cho mô hình là `tiêu đề tài liệu › đường dẫn mục` rồi nội dung; nội dung lưu trong DB
vẫn là văn bản thuần. Đoạn khóa học dùng `khóa học › chương › bài › khối`. Hai bảng đoạn có thêm `section_path`,
`content_hash` (SHA-256), `chunker_version`, `embed_model`; số chiều vector và tên mô hình lấy từ cấu hình
(`app.knowledge.ollama.embed-dimensions`, `embed-model`) và backend không khởi động nếu số chiều khác cột `vector(n)`.
Khi trả lời, đoạn kề (`chunk_index ± 1`, cùng tài liệu hoặc cùng bài, cùng quy tắc quyền đọc) của mỗi đoạn tìm được được
thêm vào sau các đoạn chính khi ngân sách `app.assistant.budget.retrieved` còn chỗ; top-k mặc định 4.

- `POST /api/v1/admin/knowledge/reindex`: chạy nền việc lập chỉ mục lại mọi tài liệu và revision khóa học có đoạn khác
  `chunker_version` hoặc `embed_model` so với cấu hình; mỗi mục được thay trong một transaction nên đoạn cũ vẫn trả lời
  đến khi đoạn mới có hiệu lực. Trả về trạng thái hiện tại; đang chạy thì không bắt đầu lượt mới.
- `GET /api/v1/admin/knowledge/reindex/status`: `state` (`IDLE`, `RUNNING`, `FINISHED`, `ABORTED`), `total`, `done`,
  `failed`, `startedTime`, `finishedTime`. Mục lỗi vẫn lỗi thời cho lượt sau; Ollama không kết nối được thì lượt `ABORTED`.

Cả hai chỉ dành cho quản trị hệ thống (phạm vi GLOBAL). SQL: `docs/sql/upgrade/2026-11-19-chunker-v2-columns.sql`
(chạy trước backend mới; rollback `.rollback.sql`). Tinh chỉnh kích thước, top-k, `min-similarity` bằng bộ eval truy hồi
(cần Ollama) rồi ghi giá trị chọn kèm số đo vào ticket.

### `open_screen`

Tham số `functionCode`, chỉ nhận mã trong danh sách màn hình của chính người dùng: các chức năng được cấp có
`routePattern` cố định (không có `:tham-số`), theo thứ tự menu, cùng nguồn với `functionDetails` của `/auth/me`. Danh sách này nằm
trong mô tả và `enum` của công cụ mỗi request. Mã của vai trò khác hoặc mã bịa nhận lại văn bản từ chối "Bạn không có
quyền mở màn hình này hoặc màn hình không tồn tại." (mô hình nói lại với người dùng) và không có `navigate`. Mã hợp lệ:
`navigate` với `path` do **server** lấy từ chức năng, `done.answer` = "Đã mở trang <tên>.". App kiểm tra `path` với menu
của người dùng trước khi chuyển; guard của route vẫn áp dụng.

### `update_profile`

Tham số tùy chọn `fullName`, `email`, `positionName` (chỉ gửi trường muốn đổi); thuộc tính khác bị bỏ qua. Mọi kiểm tra
diễn ra **trước** khi có thẻ xác nhận, theo luật của `PATCH /auth/me`: họ tên 1–255 ký tự, email hợp lệ và tối đa 100
ký tự, chức danh tối đa 255 ký tự; chuỗi rỗng ở email hoặc chức danh nghĩa là xoá. Lỗi trả lại mô hình dưới dạng văn
bản tiếng Việt để hỏi lại người dùng. Trường có giá trị mới bằng giá trị hiện tại bị bỏ; không còn trường nào:
"Hồ sơ đã có đúng giá trị này, không cần thay đổi.".

Hợp lệ: lưu một yêu cầu chờ, ghi `action_required` (giá trị cũ `from` do server đọc từ hồ sơ, không tin mô hình), rồi
`done` với "Vui lòng kiểm tra và xác nhận thay đổi bên dưới.". Khi người dùng xác nhận: `SelfProfileService.updateIfUnchanged`
giữ khóa ghi, so từng `from` với giá trị hiện tại trong cùng transaction sẽ ghi; có trường đã khác thì không ghi gì và trả
`ASSISTANT-ACTION_STALE`; nếu không thì sửa chỉ các trường đổi (kiểm tra và audit `USER_PROFILE_UPDATED` như `PATCH /auth/me`) và trả `ActionResultView` với
`data` là `/auth/me` đã làm mới. Tài khoản SSO và tài khoản còn phải đổi mật khẩu không được cung cấp công cụ này.

## Yêu cầu chờ xác nhận

Lưu ở bảng `assistant_pending_actions` ([2026-11-10-assistant-pending-actions.sql](../sql/upgrade/2026-11-10-assistant-pending-actions.sql)):
hết hạn sau `action-ttl`, gắn với người đã gây ra nó, tối đa 20 yêu cầu đang chờ mỗi người (yêu cầu cũ nhất chuyển sang
`CANCELLED` khi thêm cái thứ 21). Khởi động lại backend không làm mất yêu cầu: xác nhận vẫn được nếu còn hạn. Hàng
được giữ cùng kết cục (`PENDING`→`CONFIRMED`|`CANCELLED`|`FAILED`, kèm `result_message`) đến khi hội thoại chứa nó bị
xoá (xoá tay hoặc quá `history.retention`); yêu cầu không gắn với lượt nào bị dọn theo cùng hạn đó. "Hết hạn" không lưu:
là `PENDING` đã quá `expires_at`.

## Mã lỗi

| messageCode                  | HTTP | Khi nào                                                                            |
| ---------------------------- | ---- | ---------------------------------------------------------------------------------- |
| `ASSISTANT-AI_UNAVAILABLE`   | 200  | Ollama không phản hồi (trước stream: envelope; giữa stream: sự kiện `error`)       |
| `ASSISTANT-ACTION_NOT_FOUND` | 200  | Yêu cầu không có, hết hạn, đã xác nhận/huỷ, hoặc của người khác (cùng một mã, id không tiết lộ gì) |
| `ASSISTANT-BUSY`             | —    | Người dùng đang có một lượt chạy, hoặc server đã đủ lượt (sự kiện `error`)        |
| `ASSISTANT-RATE_LIMITED`     | —    | Người dùng đã hỏi quá `questions-per-hour` câu trong 1 giờ trượt (sự kiện `error`, kèm "thử lại sau X phút") |
| `ASSISTANT-CONVERSATION_NOT_FOUND` | 200 | Hội thoại không tồn tại hoặc của người khác (cùng một mã)                         |
| `ASSISTANT-LESSON_NOT_FOUND` | 200 | Bài học trong `context` không tồn tại, chưa xuất bản hoặc người dùng không đọc được (cùng một mã) |
| `ASSISTANT-TURN_NOT_FOUND`   | 200  | Lượt (khi đánh giá) không tồn tại hoặc của người khác (cùng một mã)                |
| `ASSISTANT-ACTION_STALE`     | 200  | Dữ liệu đã đổi từ lúc trợ lý đề xuất (khi xác nhận)                                |
| `CORE-VALIDATION_FAILED`     | 200  | Request `/stream` không hợp lệ                                                     |
| `CORE-ACCESS_DENIED`         | 403  | Người gọi không có chức năng `AI_ASSISTANT` (mọi API của trợ lý nhanh)              |
| `CORE-INTERNAL_ERROR`        | —    | Lỗi bất ngờ giữa stream (sự kiện `error`, thông báo chung)                         |

## Hội thoại đã lưu

Dùng chung cho trợ lý nhanh và `/assistant` (hỏi đáp tài liệu). Mọi API cần chức năng `AI_ASSISTANT` và chỉ thấy hội thoại
của chính người gọi; quản trị viên không đọc được nội dung qua API. Trả `BaseResponse`.

| Method | Đường dẫn                                | Ý nghĩa                                                                        |
| ------ | ---------------------------------------- | ------------------------------------------------------------------------------ |
| GET    | `/api/v1/assistant/conversations`        | Danh sách mới nhất trước (`page`, `size`, `q` tìm theo tên không phân biệt hoa thường) |
| GET    | `/api/v1/assistant/conversations/{id}`   | Hội thoại kèm các lượt, cũ nhất trước                                          |
| PATCH  | `/api/v1/assistant/conversations/{id}`   | Đổi tên: `{ "title": "…" }`, 1–80 ký tự                                        |
| DELETE | `/api/v1/assistant/conversations/{id}`   | Xoá hẳn một hội thoại cùng mọi lượt                                            |
| DELETE | `/api/v1/assistant/conversations`        | Xoá hẳn mọi hội thoại của người gọi (không đụng tới của người khác)             |

Tên mặc định là câu hỏi đầu tiên cắt còn 80 ký tự (không gọi mô hình). Mã của người khác trả
`ASSISTANT-CONVERSATION_NOT_FOUND`, giống mã không tồn tại. Một lượt (`TurnView`) có `surface` (`AGENT`|`KNOWLEDGE`),
`question`, `answer` (null nếu lỗi/dừng sớm), `citations`, `toolNames`, `navigation`, `actionId`, `action`, `status`
(`done`|`error`|`stopped`) và `createTime`. `action` (null nếu lượt không đề xuất thay đổi) là thẻ xác nhận với trạng
thái hiện tại: `id`, `tool`, `title`, `changes`, `status` (`pending`|`confirmed`|`cancelled`|`failed`|`expired`),
`expiresAt` và `message` (kết quả hoặc lý do bị từ chối). Schema: [2026-11-10-assistant-conversations.sql](../sql/upgrade/2026-11-10-assistant-conversations.sql).

## Đánh giá câu trả lời

`PUT /api/v1/assistant/turns/{turnId}/feedback` (chức năng `AI_ASSISTANT`, chỉ chủ lượt): ghi hoặc sửa 👍/👎 của một
lượt; mỗi lượt một đánh giá. Body: `helpful` (bắt buộc), `reasonCode` (chỉ khi `helpful=false`: `irrelevant`,
`incorrect`, `outdated`, `missing_source`, `other`), `note` (tối đa 2000 ký tự). Trả `{ helpful, reasonCode, note }`.
`reasonCode` sai hoặc đi với 👍, `note` quá dài trả `CORE-VALIDATION_FAILED`. Lượt của người khác hoặc không có trả
`ASSISTANT-TURN_NOT_FOUND`. `TurnView.feedback` (null nếu chưa đánh giá) cho lần mở lại hội thoại thấy lựa chọn đã lưu.
Xoá hội thoại, xoá tất cả và dọn quá hạn đều xoá đánh giá của các lượt đó. Log chỉ ghi id lượt, 👍/👎 và mã lý do, không
bao giờ ghi `note`. Schema: [2026-11-10-assistant-feedback.sql](../sql/upgrade/2026-11-10-assistant-feedback.sql).

## Giới hạn đã biết

- Câu trả lời của agent không có `[n]` mà câu đầu tiên nói bằng lời khác rằng tài liệu không có ("không được đề cập", "không
  tìm thấy", "không có thông tin"…) được coi là câu không tìm thấy: `done.answer` là câu cố định và không có nguồn. Câu có `[n]`
  vẫn giữ nguồn. Câu trả lời không có `[n]` hợp lệ nào cũng không có nguồn. Hai quy tắc này chỉ áp cho agent;
  `/knowledge/chat` giữ quy tắc cũ (không có `[n]` hợp lệ thì trả mọi đoạn).
- Khi `open_screen` từ chối, mô hình đôi khi nhắc lại mã chức năng thay vì tên màn hình. Không có gì được mở.

## Skill, prompt và cấu hình mô hình

- Prompt nằm trong `src/main/resources/assistant/`: `base.md` (giọng văn chung và quy tắc gợi ý) và, mỗi skill,
  `skills/<skill>/system.md`, `examples.md`, `skill.yml` (`description`, `tools`, `roles` rỗng là mọi vai trò, `output`).
  Mỗi lượt lưu `skill` và `prompt_version` (12 ký tự đầu của SHA-256 trên toàn bộ các file đó); sửa một file là đổi phiên bản.
- Mô hình thêm khối gợi ý ở cuối câu trả lời (dòng `@@GOI_Y@@` rồi tối đa 3 dòng `- câu hỏi`). Server cắt khối khỏi
  `delta`, `answer` và lượt đã lưu, rồi gửi trong `done.suggestions`. Khối sai định dạng bị bỏ im lặng. `/knowledge/chat/stream` có cùng
  quy tắc (`done.suggestions`), skill của lượt đó luôn là `qa_tai_lieu`.
- `app.assistant.models.{agent,grounded,classifier,generation}` mỗi mục có `chat-model`, `temperature`, `num-ctx`;
  bỏ trống mục nào thì giữ giá trị cũ (`app.knowledge.ollama.chat-model`, nhiệt độ 0,1, ngữ cảnh 8192; bộ phân loại 0 và 2048).
  `generation` dành cho tạo khóa học (chưa dùng).
- Embedding: không còn so tên `bge-m3` cố định. Mô hình nhúng lấy từ `app.knowledge.ollama.embed-model`; `OllamaClient.embed`
  từ chối vector khác 1024 chiều, nên đổi mô hình khác chiều là lỗi rõ ràng chứ không ghi dữ liệu sai.

## Tạo khóa học bằng AI: job (`/api/v1/assistant/course-jobs`)

Chuẩn bị cho đường ống tạo khóa học (outline → bài → câu hỏi → bản nháp). Mọi endpoint cần chức năng `AI_ASSISTANT`;
riêng việc dùng còn cần vai trò quản lý khóa học (INSTRUCTOR, ADMIN, SUPER_ADMIN): học viên và người xem báo cáo nhận 403.

| Method | Path | Ý nghĩa |
|---|---|---|
| POST | `/api/v1/assistant/course-jobs` | multipart `file` (.pdf/.docx/.md ≤ `app.knowledge.max-file-size`) + `inputs` (JSON). Trả job. |
| GET | `/api/v1/assistant/course-jobs` | Các job của tôi, mới nhất trước (tối đa 50). |
| GET | `/api/v1/assistant/course-jobs/{jobId}` | Trạng thái, bước, tiến độ, các bước đã có. Job của người khác: `CORE-RESOURCE_NOT_FOUND`. |
| POST | `/api/v1/assistant/course-jobs/{jobId}/cancel` | Hủy job chưa kết thúc (`CORE-VALIDATION_FAILED`/`JOB_FINISHED` nếu đã kết thúc). |
| DELETE | `/api/v1/assistant/course-jobs/{jobId}` | Xóa job khỏi danh sách, xóa bước và tài liệu nguồn; dòng job được giữ (`deleted_at`) nên vẫn tính vào hạn mức trong ngày. Job đang chạy phải hủy trước (`JOB_RUNNING`). |

`inputs`: `targetLearners` (1–500 ký tự), `level` (`BEGINNER|INTERMEDIATE|ADVANCED`), `chapters` (1–20) hoặc
`durationMinutes` (15–1200) — cần ít nhất một, `programmeId`, `competencyId` (người dùng chọn, không do model), `ownerOrgId`.

- Đơn vị sở hữu do server quyết định: giảng viên luôn là đơn vị của mình (gửi đơn vị khác là 403); ADMIN/SUPER_ADMIN bắt buộc gửi
  `ownerOrgId` và nó được kiểm theo phạm vi quản lý của họ (ngoài phạm vi là 403).
- Tệp trở thành tài liệu `personal` của người gọi, liên kết với job, và đi qua cùng đường ingest như mọi tài liệu.
- Mỗi người tối đa `app.assistant.generation.jobs-per-day` (mặc định 3) job trong 24 giờ trượt, đếm trong database nên không mất khi
  khởi động lại; vượt giới hạn: `ASSISTANT-JOB_RATE_LIMITED` kèm thời gian chờ trong `errors[0].message`, không lưu tệp nào.
- Bảng `course_generation_jobs` / `course_generation_steps` (`docs/sql/upgrade/2026-11-12-course-generation-jobs.sql`). Worker nhận
  từng bước bằng `FOR UPDATE SKIP LOCKED` sau một khóa advisory của giao dịch, nên chỉ một job chạy trên toàn hệ thống (job còn lại ở
  `queued`; job `waiting_approval` không chiếm chỗ). Khởi động lại đưa bước đang chạy về `pending`, các bước đã xong giữ nguyên đầu ra.
- Trạng thái job: `queued`, `running`, `waiting_approval`, `completed`, `failed`, `cancelled`.

### Nội dung, duyệt và bản nháp (`/api/v1/assistant/course-jobs/{jobId}/...`)

Đường ống: `summarise_sections` → `outline` (job chuyển `waiting_approval`) → **tác giả duyệt dàn ý** → các bài `lesson` (`c{chương}l{bài}`)
→ `questions` của từng bài → job `completed`. Khóa học nháp đã được tạo cùng job và các bước ghi thẳng vào đó; không còn bước "tạo bản nháp" riêng. Không bước nào sau `outline` chạy trước khi duyệt.
Chỉ chủ job gọi được (người khác: `CORE-RESOURCE_NOT_FOUND`), kể cả ghi chú nguồn (`sourceNotes`) trong nội dung từng bài.

| Method | Path | Ý nghĩa |
|---|---|---|
| GET | `.../content` | Dàn ý, `outlineLocked`, các bài (khối nội dung, ghi chú nguồn) và câu hỏi đề xuất (`decision`: `pending|accepted|rejected|added`). |
| PUT | `.../outline` | Sửa dàn ý (JSON đúng schema); chỉ trước khi duyệt, sau đó `OUTLINE_LOCKED`. |
| POST | `.../outline/approve` | Khóa dàn ý, tạo các bước bài rồi câu hỏi, job → `running`. |
| POST | `.../lessons/{key}/regenerate` | Viết lại đúng một bài; bài khác và câu hỏi giữ nguyên. |
| PUT | `.../questions/{key}/{index}` | Body `{"decision":"accepted|rejected|pending"}`. `accepted` ghi câu hỏi vào ngân hàng ngay (`decision` thành `added`, gọi lại không ghi thêm); `rejected` giữ câu hỏi ngoài ngân hàng. Câu đã `added` không loại được (`QUESTION_ALREADY_ADDED`). |
| POST | `.../retry` | Chạy lại các bước lỗi; bước đã xong giữ nguyên. |

- Mỗi bước chạy tối đa 6 lần kể cả tạo lại và thử lại (`REGENERATION_LIMIT`); dàn ý tối đa 60 bài.
- Đầu ra của model được kiểm theo schema (`GenerationSchemas`): không hợp lệ thì hỏi lại đúng một lần kèm danh sách lỗi, rồi bước `failed` với
  thông báo đọc được. Giới hạn: ≤ 20 chương, ≤ 10 bài/chương, ≤ 6 khối/bài, thân khối ≤ 6000 ký tự.
- Bản nháp được tạo bằng `LearningContentService.create` với quyền của tác giả, luôn ở trạng thái `draft` (không bao giờ tự xuất bản) và
  `course_revisions.ai_generated = true` (`docs/sql/upgrade/2026-11-13-course-revision-ai-generated.sql`).
- Chỉ câu hỏi `accepted` được ghi vào ngân hàng câu hỏi (qua `QuestionService`, kèm chương trình và năng lực do người dùng chọn, chưa gắn khóa/bài).
- Thời lượng mỗi bước ghi vào meter `assistant.generation.step.duration` (tag `kind`, `outcome`).

### Mở form tạo khóa học từ chatbot (kỹ năng `tao_khoa_hoc`, công cụ `open_course_form`)

- Công cụ không tạo gì và không đọc tệp: nó trả sự kiện `navigate` tới `/admin/courses/new` kèm `prefill` (`title`, `targetLearners`, `level`,
  `chapters`, `durationMinutes`, chỉ những trường người dùng đã nói và nằm trong giới hạn của form). Không hỏi thêm, không tra chương trình hay năng lực.
- Tệp đính kèm ở lại trình duyệt (`CourseFormPrefillStore`); chỉ khi bấm "Tạo bằng AI" trên form tệp mới được tải lên (scope `COURSE_SOURCE`) và job mới tạo.
- Kỹ năng và công cụ chỉ dành cho INSTRUCTOR, ADMIN, SUPER_ADMIN. Học viên không được cấp kỹ năng; `style.md` bảo trợ lý trả lời ngắn rằng tính năng
  dành cho giảng viên.

