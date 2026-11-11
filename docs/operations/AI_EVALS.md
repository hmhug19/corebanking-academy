# Đánh giá mô hình trợ lý AI (model evals)

Các eval chạy với Ollama thật (`qwen3:8b` tại `localhost:11434`), nên tên lớp kết thúc bằng `Eval` và **không** nằm trong lần chạy mặc định (`mvn test` chỉ lấy `*Test` và `*IT`).

```bash
mvn test -Dtest="*Eval"
```

Một lệnh chạy tất cả: `AgentToolChoiceEval` (chọn skill/công cụ, báo cáo `target/agent-tool-choice-eval.txt`) và `GoldenSetEval` (bộ vàng, báo cáo `target/golden-set-eval.txt`). Chạy riêng: `-Dtest=GoldenSetEval`.

## Bộ vàng

`src/test/resources/assistant/eval/golden-set.json`, có `version`:

| Phần | Số ca | Đo |
|---|---|---|
| `answers` | 38 | độ phủ ý đáp án, nguồn trích đúng |
| `notFound` | 8 | trả đúng câu "không tìm thấy" và không có nguồn |
| `injection` | 10 | không làm theo chỉ dẫn nằm trong tài liệu/bài học, không gọi công cụ hành động |
| `toolChoice` | 12 | chọn đúng skill và công cụ (gồm các ca trong bài học) |

Truy hồi **không** nằm trong bộ này: mỗi ca nêu các đoạn mà một lần tìm tốt sẽ trả về, mô hình nhận chúng đúng như công cụ `search_documents` đưa (`SearchDocumentsTool.resultFor`). Lỗi ở đây là do prompt hoặc mô hình.

`GoldenSetTest` (chạy mặc định) kiểm bộ vàng đúng cấu trúc, đủ số ca, mọi đoạn được nhắc đều tồn tại.

## Bộ vàng truy hồi

`src/test/resources/assistant/eval/retrieval/`: `fixtures.json` (6 tài liệu mẫu: PDF một cột, PDF hai cột có bảng, DOCX có tiêu đề, danh sách đánh số và bảng, 3 tệp Markdown gồm tài liệu cá nhân và tài liệu đơn vị) và `retrieval-set.json` (40 ca: `plain` 16, `codes` 10, `follow_up` 6, `empty` 4, `permission` 4). Mỗi ca nêu người hỏi, tài liệu và cụm `marker` của mục phải có trong top k, hoặc tài liệu không được trả về (`forbidden`).

```bash
mvn test -Dtest=RetrievalEval
```

`RetrievalEval` dựng các tệp (PDF cần một font TrueType có tiếng Việt: hệ thống tự tìm, hoặc `-Dretrieval.eval.font=<đường dẫn .ttf>`), cho qua extractor, chunker và `bge-m3` thật, lưu vào pgvector (Testcontainers), rồi tìm từng ca bằng người hỏi của nó. Báo cáo `target/retrieval-eval.txt` có recall@5, recall@20, MRR, tỉ lệ rỗng đúng và số lần rò quyền xem theo nhóm. Chưa duyệt thì chỉ tham khảo, nhưng một tài liệu trả về cho người không được xem luôn làm eval fail. `RetrievalSetTest` (chạy mặc định) kiểm bộ tệp nhất quán và extractor đọc lại được các tệp mẫu.

Số nền: **chưa đo** (cần Docker và Ollama với `bge-m3`; xem ghi chú trong ticket rag-quality 01). Khóa học mẫu và câu hỏi nối tiếp có ngữ cảnh được thêm ở ticket 02 và 09.

## Duyệt

Bộ vàng do đội phát triển soạn từ tài liệu và khóa học mẫu. Khi nghiệp vụ duyệt, điền `approval.approvedBy` và `approval.approvedAt` trong file. Chưa duyệt: báo cáo ghi `NOT APPROVED: results are ADVISORY` và ngưỡng không bị ép (eval không fail vì điểm thấp). Đã duyệt: ngưỡng được ép (độ phủ ý ≥ 85%, các phần còn lại ≥ 90%).

Đổi bộ vàng: tăng `version`, và xóa `approval` để duyệt lại.
