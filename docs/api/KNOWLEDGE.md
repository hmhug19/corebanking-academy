# Kho tri thức "Trợ lý AI"

Một kho tài liệu **dùng chung cho mọi người dùng và mọi vai trò**. Người dùng tải lên `.pdf`, `.md`, `.docx`;
backend xử lý nền (trích xuất theo trang/dòng, chia đoạn, nhúng vector `bge-m3`) rồi cho phép hỏi đáp dựa trên
đúng các tài liệu đó. Mô hình chạy cục bộ qua Ollama; không gửi dữ liệu ra ngoài máy chủ.

- Schema: [learning_hub_knowledge.sql](../sql/learning_hub_knowledge.sql) (cần pgvector; chạy sau Identity và Learning).
- Chức năng menu `AI_ASSISTANT` (route `/assistant`) nằm trong seed Identity và được cấp cho mọi vai trò.
- Request mẫu: [Postman](../postman/knowledge.postman_collection.json). Swagger: `/swagger-ui.html`.
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
| `app.knowledge.retrieval.*`           | `top-k 5`, `0.35`        | Số đoạn lấy về và ngưỡng cosine tối thiểu        |
| `app.knowledge.ollama.base-url`       | `http://localhost:11434` | Địa chỉ Ollama                                   |
| `app.knowledge.ollama.chat-model`     | `qwen3:8b`               | Mô hình trả lời                                  |
| `app.knowledge.ollama.embed-model`    | `bge-m3`                 | Mô hình nhúng (1024 chiều)                       |

## Tài liệu

### `POST /api/v1/knowledge/documents` (multipart, part `file`)

Trả ngay HTTP **201** với `status: "queued"`; xử lý diễn ra nền. Kiểm tra đồng bộ trước khi xếp hàng:
phần mở rộng, kích thước (1 byte đến `max-file-size`), MIME khai báo phải khớp phần mở rộng (hoặc rỗng /
`application/octet-stream`), và nội dung thật (PDF bắt đầu `%PDF-`; DOCX là zip có `word/document.xml`; Markdown là
UTF-8 nghiêm ngặt, không có byte NUL). Loại tệp lưu là loại backend nhận diện.

### `GET /api/v1/knowledge/documents`

Toàn bộ kho dùng chung, mới nhất trước. Tham số: `page` (từ 0), `size` (1..100, mặc định 20), `mine=true` (chỉ
tài liệu của người gọi), `status` (lặp lại được: `?status=queued&status=processing`).

### `GET /api/v1/knowledge/documents/{documentId}`

Một tài liệu; không có thì `CORE-RESOURCE_NOT_FOUND`.

### `KnowledgeDocumentView`

```json
{
  "id": 42,
  "fileName": "Quy-trinh-EOD.pdf",
  "mimeType": "application/pdf",
  "sizeBytes": 812345,
  "status": "processing",
  "errorMessage": null,
  "pageCount": 12,
  "chunkCount": 0,
  "uploadedBy": { "userId": "000005", "fullName": "Giảng viên Demo" },
  "createdAt": "2026-10-22T08:00:00Z",
  "processedAt": null
}
```

`status`: `queued` (chờ) → `processing` (đang xử lý) → `ready` (tìm kiếm được) hoặc `failed` (kèm `errorMessage`
tiếng Việt). `pageCount` chỉ có với PDF đã xử lý. Không trả `storageKey` hay đường dẫn tệp.

### Mã lỗi (HTTP 200, `status: ERROR`)

| messageCode                       | Khi nào                                                    |
| --------------------------------- | ---------------------------------------------------------- |
| `KNOWLEDGE-FILE_TYPE_UNSUPPORTED` | Phần mở rộng không phải .pdf, .md, .docx                   |
| `KNOWLEDGE-FILE_INVALID`          | Tệp rỗng, MIME mâu thuẫn, hoặc nội dung không khớp loại tệp |
| `KNOWLEDGE-FILE_TOO_LARGE`        | Vượt `max-file-size`                                       |
| `KNOWLEDGE-AI_UNAVAILABLE`        | Ollama không phản hồi (dùng cho chat)                      |
| `CORE-VALIDATION_FAILED`          | `page`/`size`/`status` không hợp lệ                        |
| `CORE-RESOURCE_NOT_FOUND`         | Không có tài liệu                                          |

## Hỏi đáp (chat)

Một lượt, độc lập: backend không giữ hội thoại. Câu hỏi 1–300 ký tự sau khi bỏ khoảng trắng hai đầu. Quy trình:
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
        "snippet": "CIF (Customer Information File) là mã khách hàng duy nhất…"
      }
    ]
  }
}
```

`page` là null với `.md` và `.docx`. `snippet` tối đa 280 ký tự, cắt ở ranh giới từ và thêm "…".
Không tìm thấy: `found = false`, `answer` là câu cố định, `citations = []`.

### POST /api/v1/knowledge/chat/stream

Cùng đầu vào, trả `application/x-ndjson` (mỗi dòng một JSON, không bọc `BaseResponse`, có header
`X-Accel-Buffering: no`). Client nên gửi `Accept: application/x-ndjson, application/json` để một lời từ chối trước khi
stream (envelope JSON) không bị trả 406.

| type        | Trường                                | Ý nghĩa                                                       |
| ----------- | ------------------------------------- | ------------------------------------------------------------- |
| `retrieved` | `count`                               | Số đoạn đủ liên quan, gửi ngay khi tìm xong                   |
| `delta`     | `text`                                | Một đoạn văn bản của câu trả lời, nối theo thứ tự             |
| `done`      | `found`, `citations`                  | Kết thúc; `citations` như trên, rỗng khi `found = false`      |
| `error`     | `messageCode`, `message`              | Lỗi giữa chừng (Ollama dừng sau khi đã bắt đầu), kết thúc dòng |

Khi không có đoạn nào đủ liên quan: `retrieved(0)`, một `delta` là câu cố định, `done(found=false)`.
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
