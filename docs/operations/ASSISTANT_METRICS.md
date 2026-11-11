# Số liệu vận hành của trợ lý AI

Hai trợ lý (trợ lý nhanh và hỏi đáp tài liệu) ghi Micrometer meter, tên bắt đầu bằng `assistant.`. Tag chỉ nhận giá trị
trong một tập nhỏ cố định (màn hình, trạng thái, tên công cụ/skill đã đăng ký, mã lỗi, mã lý do đánh giá); **không có
mã người dùng, mã hội thoại, câu hỏi hay câu trả lời** trong tên meter hoặc tag. Nội dung ghi chú đánh giá cũng không vào
meter hay log.

## Bật và đọc

Mặc định chưa có gì thay đổi: app port chỉ phơi `health` và `info`, và `/actuator/metrics` không mở. Meter vẫn được ghi
trong bộ nhớ; để đọc, cho actuator chạy ở một management port chỉ máy chủ truy cập được:

```properties
MANAGEMENT_SERVER_PORT=8020
MANAGEMENT_SERVER_ADDRESS=127.0.0.1
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,metrics
```

Đó là các biến môi trường mà Spring Boot tự ánh xạ vào `management.server.port`, `management.server.address` và
`management.endpoints.web.exposure.include`; `application.yml` không đổi. Khi `MANAGEMENT_SERVER_PORT` được đặt, **mọi** endpoint actuator, kể cả `health`, chuyển sang port đó: sửa healthcheck của
Dockerfile, `deploy/ci/deploy-ci.sh` và workflow CI trỏ tới `http://127.0.0.1:8020/actuator/health` trước khi bật.
Không publish port quản lý ra ngoài Docker host.

```bash
curl -s http://127.0.0.1:8020/actuator/metrics | grep assistant
curl -s "http://127.0.0.1:8020/actuator/metrics/assistant.turn.duration?tag=surface:agent&tag=status:done"
```

Đưa vào scraper (Prometheus) là quyết định vận hành riêng: cần thêm `micrometer-registry-prometheus` và phơi
`prometheus` trên cùng management port. Chưa làm.

## Các meter

| Meter | Loại | Tag | Cho biết |
| ----- | ---- | --- | -------- |
| `assistant.turn.duration` | Timer | `surface` (`agent`/`knowledge`), `status` (`done`/`error`/`stopped`) | Một lượt dài bao lâu, và bao nhiêu lượt lỗi hoặc bị dừng |
| `assistant.turn.first_token` | Timer | `surface` | Từ lúc hỏi đến khi có chữ đầu tiên. Tăng cao: mô hình nghẽn hoặc nạp lại |
| `assistant.turn.steps` | Summary | `surface` | Số lần gọi mô hình trong một lượt của trợ lý nhanh. Gần `max-steps`: mô hình lặp công cụ |
| `assistant.tool.calls` | Counter | `tool` (tên công cụ; `unknown` cho tên mô hình bịa) | Công cụ nào được gọi |
| `assistant.skill.turns` | Counter | `skill` | Lượt theo skill (`qa_tai_lieu`, `dieu_huong`, `ho_so`, `tro_chuyen`) |
| `assistant.answers.not_found` | Counter | `surface` | Câu trả lời "không tìm thấy trong tài liệu". Tỉ lệ so với số lượt cho biết kho tài liệu có thiếu |
| `assistant.errors` | Counter | `surface`, `code` | Lượt kết thúc bằng lỗi, theo mã (`ASSISTANT-AI_UNAVAILABLE`, `CORE-INTERNAL_ERROR`…) |
| `assistant.refusals` | Counter | `surface`, `reason` (`rate_limited`/`busy`) | Câu hỏi bị từ chối trước khi chạy |
| `assistant.feedback` | Counter | `surface`, `helpful` (`true`/`false`), `reason` (mã lý do hoặc `none`) | 👍/👎 và lý do. Mỗi lần người dùng gửi hoặc sửa đánh giá đếm một lần |

Meter nằm trong bộ nhớ của một instance và về 0 khi khởi động lại; so sánh theo tốc độ, không theo giá trị tuyệt đối.

## Khi nào chỉnh hoặc nâng cấp

- `assistant.turn.first_token` p95 vượt vài giây ổn định: kiểm tra Ollama (nạp mô hình, `num_ctx`, tải GPU/CPU).
- `assistant.errors{code=ASSISTANT-AI_UNAVAILABLE}` tăng: Ollama hoặc đường mạng tới nó.
- `assistant.answers.not_found` cao: thiếu tài liệu hoặc ngưỡng truy xuất quá chặt; xem thêm 👎 lý do `missing_source`.
- `assistant.feedback{helpful=false}` tập trung ở một `reason`: `incorrect` và `outdated` trỏ tới tài liệu hoặc prompt,
  `irrelevant` trỏ tới truy xuất hoặc chọn skill. Mỗi lượt lưu `prompt_version` để lần ngược về prompt.
- `assistant.refusals{reason=busy}` nhiều: người dùng gửi song song; `rate_limited` nhiều: cân nhắc `questions-per-hour`.
