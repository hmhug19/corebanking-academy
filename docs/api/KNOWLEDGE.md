# Kho tri thức "Trợ lý AI"

Một kho tài liệu **dùng chung cho mọi người dùng và mọi vai trò**. Người dùng tải lên `.pdf`, `.md`, `.docx`;
backend xử lý nền (trích xuất theo trang/dòng, chia đoạn, nhúng vector `bge-m3`) rồi cho phép hỏi đáp dựa trên
đúng các tài liệu đó. Mô hình chạy cục bộ qua Ollama; không gửi dữ liệu ra ngoài máy chủ.

- Schema: [learning_hub_knowledge.sql](../sql/learning_hub_knowledge.sql) (cần pgvector; chạy sau Identity và Learning).
- Chức năng menu `AI_ASSISTANT` (route `/assistant`) nằm trong seed Identity và được cấp cho mọi vai trò.
- Request mẫu: [Postman](../postman/knowledge.postman_collection.json). Swagger: `/swagger-ui.html`.
- Mọi API dưới `/api/v1/knowledge` (tải lên, danh sách, chi tiết, hỏi đáp) yêu cầu người gọi có chức năng `AI_ASSISTANT`,
  thiếu thì `CORE-ACCESS_DENIED` (HTTP 403). Hỏi đáp dùng chung hạn mức câu hỏi/giờ và lượt chạy với trợ lý nhanh
  (`ASSISTANT-BUSY`, `ASSISTANT-RATE_LIMITED`; xem [ASSISTANT.md](ASSISTANT.md)).
- Mọi API dưới `/api/v1/knowledge`, yêu cầu JWT hợp lệ, trả `BaseResponse` (xem [API_CONTRACT.md](API_CONTRACT.md))
  trừ luồng NDJSON của chat.

## Cấu hình (`application.yml`, biến môi trường ghi đè)

| Khóa                                  | Mặc định                 | Ý nghĩa                                          |
| ------------------------------------- | ------------------------ | ------------------------------------------------ |
| `app.knowledge.storage-dir`           | `./data/knowledge`       | Thư mục tệp tải lên (tên tệp là UUID)            |
| `app.knowledge.max-file-size`         | `20MB`                   | Dung lượng tối đa mỗi tệp                        |
| `app.knowledge.worker.poll-interval`  | `2s`                     | Chu kỳ worker nhận tài liệu `queued`             |
| `app.knowledge.worker.concurrency`    | `1`                      | Số tài liệu xử lý đồng thời                      |
| `app.knowledge.worker.max-attempts`   | `3`                      | Số lần thử khi Ollama không phản hồi             |
| `app.knowledge.chunk.*`               | 600 / 800 / 80           | Token mục tiêu / tối đa / chồng lấn (ước lượng)  |
| `app.knowledge.retrieval.*`           | `top-k 4`, `0.45`        | Số đoạn lấy về và ngưỡng cosine tối thiểu        |
| `app.knowledge.ollama.base-url`       | `http://localhost:11434` | Địa chỉ Ollama                                   |
| `app.knowledge.ollama.chat-model`     | `qwen3:8b`               | Mô hình trả lời                                  |
| `app.knowledge.ollama.embed-model`    | `bge-m3`                 | Mô hình nhúng (1024 chiều)                       |
| `app.knowledge.ollama.keep-alive`     | `-1s`                    | Thời gian Ollama giữ model; âm là giữ đến khi tắt |

## Tài liệu

### `POST /api/v1/knowledge/documents` (multipart, part `file`)

Trả ngay HTTP **201** với `status: "queued"`; xử lý diễn ra nền. Kiểm tra đồng bộ trước khi xếp hàng:
phần mở rộng, kích thước (1 byte đến `max-file-size`), MIME khai báo phải khớp phần mở rộng (hoặc rỗng /
`application/octet-stream`), và nội dung thật (PDF bắt đầu `%PDF-`; DOCX là zip có `word/document.xml`; Markdown là
UTF-8 nghiêm ngặt, không có byte NUL). Loại tệp lưu là loại backend nhận diện.

Tham số tùy chọn `scope` quyết định ai tìm thấy tài liệu: `personal` (chỉ người tải lên), `unit` (đơn vị của người tải lên
và các đơn vị con; **mặc định**) hoặc `global` (mọi người; chỉ ADMIN/SUPER_ADMIN, vai trò khác nhận HTTP 403). Giá trị
khác trả lỗi validation trên field `scope`. Tài liệu có sẵn trước khi có phạm vi được chuyển thành `global`.

Tham số tùy chọn `title` là tên hiển thị (mặc định là tên tệp). **Chặn trùng:** nếu người gọi đã có một tài liệu (không `failed`)
cùng checksum SHA-256 trong cùng phạm vi (cùng `scope`, và cùng đơn vị với `unit`), không lưu gì và trả HTTP **200** với tài
liệu đã có và `duplicate: true`. Tệp giống hệt của người khác, hoặc của chính mình ở phạm vi khác, không phải trùng.
Tài liệu cũ chưa có checksum được backend tính bù từ tệp lưu một lần sau khi khởi động.

### `PATCH /api/v1/knowledge/documents/{documentId}` (JSON `{"title": "..."}`)

Đổi tên hiển thị (và tên trong trích dẫn mới); tên tệp gốc không đổi. Người tải lên, hoặc ADMIN/SUPER_ADMIN trong phạm vi
(cùng quy tắc với xóa); người khác nhận HTTP 403, tài liệu không đọc được trả not found. Tên rỗng hoặc dài quá 255 ký tự:
validation `INVALID_TITLE` trên `title`.

### `PUT /api/v1/knowledge/documents/{documentId}/file` (multipart, part `file`)

Thay bản mới, giữ nguyên id. Tệp được kiểm tra như khi tải lên và xếp hàng ở `replacement.status = "queued"`; worker nhúng
tệp mới rồi, trong một transaction, đổi tệp và các đoạn. Trong lúc chờ bản cũ vẫn trả lời. Nếu tệp mới lỗi, bản cũ được
giữ và `replacement` ở `failed` kèm `errorMessage`; có thể thay lại. Quyền như `PATCH`. Chỉ tài liệu `ready` (`NOT_READY`
nếu không); đã có bản thay thế `queued`/`processing` thì `REPLACING`. Tệp giống hệt tệp hiện tại: không đổi gì,
`duplicate: true`. Xóa tài liệu cũng xóa tệp thay thế đang chờ.

### `GET /api/v1/knowledge/documents/duplicates`

Các nhóm tài liệu của người gọi có cùng checksum trong cùng phạm vi: `[{ "checksum": "...", "documents": [KnowledgeDocumentView] }]`,
mới nhất trước trong mỗi nhóm. Không xóa gì tự động; người dùng xóa từng bản bằng `DELETE`.

### `GET /api/v1/knowledge/documents`

Các tài liệu người gọi được đọc (của mình, của đơn vị mình và đơn vị cha, và `global`), mới nhất trước. Tham số: `page` (từ 0), `size` (1..100, mặc định 20), `mine=true` (chỉ
tài liệu của người gọi), `status` (lặp lại được: `?status=queued&status=processing`).

### `GET /api/v1/knowledge/documents/{documentId}`

Một tài liệu; không có hoặc người gọi không được đọc thì `CORE-RESOURCE_NOT_FOUND`.

### `GET /api/v1/knowledge/documents/{documentId}/text`

Văn bản đã trích quanh một đoạn được trích dẫn, cho màn xem nguồn. Tham số: `around` (id đoạn; bỏ trống thì bắt đầu từ
đoạn đầu tiên), `span` (0..20, mặc định 2: số đoạn kề mỗi bên). Trả `DocumentTextView`: `documentId`, `fileName`,
`aroundChunkId`, `chunks` (theo thứ tự đọc; mỗi đoạn có `chunkId`, `chunkIndex`, `sectionPath`, `page`, `lineStart`,
`lineEnd`, `text`), `hasMoreBefore`, `hasMoreAfter` (còn đoạn ngoài danh sách; tăng `span` để đọc thêm). Cùng quy tắc nhìn
thấy như tìm kiếm: tài liệu người gọi không tìm được (của người khác, hoặc nguồn khóa học `course_source`), tài liệu đã
xóa, hoặc `around` không thuộc tài liệu đều trả `CORE-RESOURCE_NOT_FOUND`. Không có endpoint trả tệp gốc.

### `DELETE /api/v1/knowledge/documents/{documentId}`

Xóa tệp, các đoạn và bản ghi, nên câu hỏi sau không còn trích dẫn tài liệu. Người tải lên luôn xóa được; ADMIN/SUPER_ADMIN
xóa được tài liệu `global` (phạm vi toàn hệ thống) hoặc `unit` nằm trong phạm vi của mình; tài liệu `personal` chỉ chủ
sở hữu xóa. Không đủ quyền: HTTP 403. Tài liệu không đọc được: not found. Tài liệu đang `processing`: lỗi validation
`PROCESSING` trên `documentId`; thử lại sau khi xử lý xong.

### `KnowledgeDocumentView`

```json
{
  "id": 42,
  "fileName": "Quy-trinh-EOD.pdf",
  "title": "Quy trình EOD",
  "mimeType": "application/pdf",
  "sizeBytes": 812345,
  "scope": "unit",
  "scopeOrgId": 7,
  "status": "processing",
  "errorMessage": null,
  "pageCount": 12,
  "chunkCount": 0,
  "uploadedBy": { "userId": "000005", "fullName": "Giảng viên Demo" },
  "createdAt": "2026-10-22T08:00:00Z",
  "processedAt": null,
  "duplicate": false,
  "replacement": null
}
```

`replacement` là `{ "status": "queued|processing|failed", "fileName": "...", "errorMessage": null }` khi có tệp thay thế.
Trích dẫn của chat và `fileName` của `DocumentTextView` mang `title`.

`status`: `queued` (chờ) → `processing` (đang xử lý) → `ready` (tìm kiếm được) hoặc `failed` (kèm `errorMessage`
tiếng Việt). `pageCount` chỉ có với PDF đã xử lý. Không trả `storageKey` hay đường dẫn tệp.

### Mã lỗi (HTTP 200, `status: ERROR`)

| messageCode                       | Khi nào                                                    |
| --------------------------------- | ---------------------------------------------------------- |
| `KNOWLEDGE-FILE_TYPE_UNSUPPORTED` | Phần mở rộng không phải .pdf, .md, .docx                   |
| `KNOWLEDGE-FILE_INVALID`          | Tệp rỗng, MIME mâu thuẫn, hoặc nội dung không khớp loại tệp |
| `KNOWLEDGE-FILE_TOO_LARGE`        | Vượt `max-file-size`                                       |
| `KNOWLEDGE-AI_UNAVAILABLE`        | Ollama không phản hồi (dùng cho chat)                      |
| `CORE-VALIDATION_FAILED`          | `page`/`size`/`status` không hợp lệ; `INVALID_TITLE`, `NOT_READY`, `REPLACING`, `PROCESSING` trên `title`/`documentId` |
| `CORE-RESOURCE_NOT_FOUND`         | Không có tài liệu                                          |

## Hỏi đáp (chat)

`/chat` (không stream) là một lượt độc lập, không lưu. `/chat/stream` lưu lượt vào hội thoại dùng chung với trợ lý nhanh
(xem [ASSISTANT.md](ASSISTANT.md#hội-thoại-đã-lưu)). Câu hỏi 1–300 ký tự sau khi bỏ khoảng trắng hai đầu. Quy trình:
nhúng câu hỏi (bge-m3) → tìm `top-k` đoạn gần nhất (cosine) → loại đoạn dưới `min-similarity` → nếu không còn đoạn nào,
trả đúng câu "Không tìm thấy thông tin này trong tài liệu." mà **không gọi model** → ngược lại gửi các đoạn làm ngữ cảnh
cho `qwen3:8b` (`think: false`, `temperature 0.1`). Trích dẫn dựng từ metadata của các đoạn, không phân tích từ văn bản
model; nếu model tự trả đúng câu không tìm thấy thì `found = false` và bỏ trích dẫn.

### POST /api/v1/knowledge/chat

Cần Bearer token (mọi vai trò). Trả `BaseResponse<ChatAnswerView>`.

```json
{ "question": "CIF là gì?" }
```

```json
{
  "status": "SUCCESS",
  "data": {
    "answer": "CIF là mã khách hàng duy nhất [1].",
    "found": true,
    "citations": [
      {
        "n": 1,
        "documentId": 42,
        "fileName": "Quy-trinh-EOD.pdf",
        "page": 3,
        "lineStart": 13,
        "lineEnd": 27,
        "snippet": "CIF (Customer Information File) là mã khách hàng duy nhất…",
        "sectionPath": "Khách hàng › CIF",
        "chunkId": 905
      }
    ]
  }
}
```

`chunkId` là đoạn tài liệu được trích dẫn (bỏ với bài học và với lượt lưu từ trước khi có trường này), dùng cho `GET /documents/{id}/text?around=`. `page` là null với `.md` và `.docx`. `snippet` tối đa 280 ký tự, cắt ở ranh giới từ và thêm "…".
`citations` chỉ gồm các đoạn mà `answer` ghi `[n]` (dạng `[1]`, `[2][3]`, `[1, 2]`), theo thứ tự xếp hạng, mỗi đoạn một
lần, `n` giữ đúng số trong prompt (có thể không liên tiếp). `[n]` ngoài khoảng bị bỏ qua; nếu không có `[n]` hợp lệ nào thì
trả mọi đoạn đã đưa vào prompt. Mọi trường của nguồn lấy từ metadata của đoạn, không lấy từ văn bản model. Trước khi đưa
vào prompt, các đoạn có nội dung trùng (tệp tải lên lại) chỉ giữ đoạn liên quan nhất.
Không tìm thấy: `found = false`, `answer` là câu cố định, `citations = []`.

### POST /api/v1/knowledge/chat/stream

Đầu vào thêm `conversationId` tuỳ chọn (bỏ trống để bắt đầu hội thoại mới; mã của người khác là envelope
`ASSISTANT-CONVERSATION_NOT_FOUND`). Với câu hỏi tiếp theo, các lượt trước của hội thoại được đưa cho mô hình làm lịch sử
(tối đa 16 000 ký tự cùng câu hỏi mới); việc tìm đoạn vẫn chỉ dùng câu hỏi mới. Trả `application/x-ndjson` (mỗi dòng một JSON, không bọc `BaseResponse`, có header
`X-Accel-Buffering: no`). Client nên gửi `Accept: application/x-ndjson, application/json` để một lời từ chối trước khi
stream (envelope JSON) không bị trả 406.

| type        | Trường                                | Ý nghĩa                                                       |
| ----------- | ------------------------------------- | ------------------------------------------------------------- |
| `conversation` | `conversationId`                   | Dòng đầu của stream: hội thoại lưu lượt này                   |
| `retrieved` | `count`                               | Số đoạn đủ liên quan, gửi ngay khi tìm xong                   |
| `delta`     | `text`                                | Một đoạn văn bản của câu trả lời, nối theo thứ tự             |
| `done`      | `found`, `answer`, `citations`, `turnId`, `suggestions` | Kết thúc; `turnId` là lượt đã lưu, dùng để gửi đánh giá (bỏ khi không lưu được); `answer` là văn bản cuối cùng (client thay văn bản đã nhận bằng nó); `citations` như trên, rỗng khi `found = false`; `suggestions` là tối đa 3 gợi ý hỏi tiếp, bỏ khi không có |
| `error`     | `messageCode`, `message`              | Lỗi giữa chừng (Ollama dừng sau khi đã bắt đầu), kết thúc dòng |

Khi không có đoạn nào đủ liên quan: `retrieved(0)`, một `delta` là câu cố định, `done(found=false)`.
Khi mô hình tự trả lời "không tìm thấy" (kể cả kèm `[n]`), `done` có `found=false`, `answer` là đúng câu cố định và `citations=[]`.
Câu hỏi không hợp lệ hoặc AI chưa sẵn sàng **trước khi** stream bắt đầu là envelope JSON `status: ERROR` như `/chat`.

| messageCode                | Khi nào                                       |
| -------------------------- | --------------------------------------------- |
| `CORE-VALIDATION_FAILED`   | Câu hỏi trống hoặc dài quá 300 ký tự          |
| `KNOWLEDGE-AI_UNAVAILABLE` | Ollama không phản hồi (nhúng hoặc sinh câu trả lời) |

## Xử lý nền

Bảng `knowledge_documents` đồng thời là hàng đợi: worker định kỳ nhận bản ghi `queued` bằng
`FOR UPDATE SKIP LOCKED` rồi trích xuất → chia đoạn → nhúng (lô 16) → lưu **trong một transaction** cùng lúc đặt
`ready`, nên tìm kiếm không bao giờ thấy tài liệu dở dang. Ollama không phản hồi: tài liệu quay lại hàng đợi, tối đa
`max-attempts` lần rồi `failed`. Khi khởi động, bản ghi còn `processing` được đưa về `queued`. Worker không đọc
security context và không ghi nội dung tài liệu vào log.

| Nguyên nhân                      | Kết quả                                                                                          |
| -------------------------------- | ------------------------------------------------------------------------------------------------ |
| Ollama không phản hồi (còn lượt) | Về `queued`                                                                                      |
| Ollama không phản hồi (hết lượt) | `failed`: "Không kết nối được dịch vụ AI sau 3 lần thử. Vui lòng tải lại tệp sau."               |
| PDF hỏng hoặc có mật khẩu        | `failed`: "Không đọc được nội dung PDF. Tệp có thể bị hỏng hoặc được đặt mật khẩu."              |
| PDF không có lớp văn bản         | `failed`: "PDF không có lớp văn bản (có thể là bản scan). Hệ thống chưa hỗ trợ nhận dạng chữ."   |
| Không có chữ nào                 | `failed`: "Tài liệu không có nội dung văn bản."                                                  |
| Lỗi khác                         | `failed`: "Không xử lý được tài liệu. Vui lòng thử lại hoặc liên hệ quản trị."                   |

## Số trang và số dòng (dùng cho trích dẫn)

- PDF: trang từ 1; dòng đánh lại từ 1 mỗi trang theo thứ tự PDFBox (`setSortByPosition(true)`).
- Markdown: dòng của tệp, từ 1, đếm cả dòng trống; không có trang.
- DOCX: không có trang; mỗi đoạn hoặc mỗi hàng bảng là một "dòng" (hiển thị là "đoạn"); bỏ header, footer, chú thích.
- Một chunk không bao giờ trải qua hai trang; `line_start`/`line_end` là dòng không trống đầu và cuối của chunk.
