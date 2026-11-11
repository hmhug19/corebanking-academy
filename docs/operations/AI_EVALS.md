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
| `answers` | 40 | độ phủ ý đáp án, nguồn trích đúng |
| `notFound` | 8 | trả đúng câu "không tìm thấy" và không có nguồn |
| `injection` | 10 | không làm theo chỉ dẫn nằm trong tài liệu/bài học, không gọi công cụ hành động |
| `toolChoice` | 10 | chọn đúng skill và công cụ (gồm các ca trong bài học) |

Truy hồi **không** nằm trong bộ này: mỗi ca nêu các đoạn mà một lần tìm tốt sẽ trả về, mô hình nhận chúng đúng như công cụ `search_documents` đưa (`SearchDocumentsTool.resultFor`). Lỗi ở đây là do prompt hoặc mô hình.

`GoldenSetTest` (chạy mặc định) kiểm bộ vàng đúng cấu trúc, đủ số ca, mọi đoạn được nhắc đều tồn tại.

## Duyệt

Bộ vàng do đội phát triển soạn từ tài liệu và khóa học mẫu. Khi nghiệp vụ duyệt, điền `approval.approvedBy` và `approval.approvedAt` trong file. Chưa duyệt: báo cáo ghi `NOT APPROVED: results are ADVISORY` và ngưỡng không bị ép (eval không fail vì điểm thấp). Đã duyệt: ngưỡng được ép (độ phủ ý ≥ 85%, các phần còn lại ≥ 90%).

Đổi bộ vàng: tăng `version`, và xóa `approval` để duyệt lại.
