# Trợ lý nhanh (agent có công cụ)

Một chatbot nhỏ trong app, tách khỏi màn hình "Trợ lý AI" (`/assistant`). Backend chạy vòng lặp agent với `qwen3:8b`
trên Ollama cục bộ: mô hình có thể gọi công cụ để tìm tài liệu, mở màn hình của chính người dùng hoặc đề xuất sửa hồ sơ
cá nhân. Công cụ chỉ **đọc** chạy ngay; công cụ **thay đổi dữ liệu** không bao giờ chạy trong vòng lặp: backend giữ
thành một yêu cầu chờ và chỉ chạy khi người dùng xác nhận bằng một request riêng, dưới quyền của chính họ.

- Request mẫu: [Postman](../postman/assistant.postman_collection.json). Swagger: `/swagger-ui.html`.
- Mọi API dưới `/api/v1/assistant/agent`, yêu cầu JWT hợp lệ (mọi vai trò), trả `BaseResponse` (xem
  [API_CONTRACT.md](API_CONTRACT.md)) trừ luồng NDJSON của `/stream`.
- Chỉ gọi Ollama cục bộ; không gửi dữ liệu ra ngoài máy chủ. Nhật ký chỉ ghi tên công cụ, số lượng và thời gian,
  không ghi câu hỏi, câu trả lời hay giá trị hồ sơ.

## Cấu hình (`application.yml`, biến môi trường ghi đè)

| Khóa                                   | Mặc định | Ý nghĩa                                                          |
| -------------------------------------- | -------- | ---------------------------------------------------------------- |
| `app.assistant.agent.max-steps`        | `4`      | Số lần gọi mô hình tối đa cho một lượt (`ASSISTANT_AGENT_MAX_STEPS`) |
| `app.assistant.agent.history-messages` | `20`     | Số tin nhắn tối đa trong request (10 lượt)                       |
| `app.assistant.agent.action-ttl`       | `10m`    | Thời hạn của một yêu cầu chờ xác nhận (`ASSISTANT_ACTION_TTL`)   |

Mô hình, URL và timeout dùng chung cấu hình `app.knowledge.ollama.*` (xem [KNOWLEDGE.md](KNOWLEDGE.md)). Mỗi lần gọi
mô hình dùng `think: false`, `temperature 0.1`, `num_ctx 8192`.

## POST /api/v1/assistant/agent/stream

Một lượt hội thoại. Trả `application/x-ndjson`, mỗi dòng một sự kiện JSON, không bọc `BaseResponse`, có header
`X-Accel-Buffering: no`. Client nên gửi `Accept: application/x-ndjson, application/json` để lời từ chối trước khi stream
(envelope JSON) không bị trả 406.

```json
{
  "messages": [
    { "role": "user", "content": "CIF là gì?" },
    { "role": "assistant", "content": "CIF là mã khách hàng duy nhất… [1]" },
    { "role": "user", "content": "Mở trang quản lý người dùng" }
  ]
}
```

- 1–20 tin nhắn; `role` chỉ `user` hoặc `assistant`, vai trò khác là `CORE-VALIDATION_FAILED`. Tin nhắn cuối cùng phải là
  `user`. Nội dung được bỏ khoảng trắng hai đầu: `user` 1–500 ký tự; `assistant` tối đa 4000 (dài hơn bị cắt, không từ
  chối vì đó là câu trả lời trước đó của chính backend).
- Hội thoại chỉ là văn bản. Backend không nhận lời gọi công cụ hay kết quả công cụ từ client; chúng chỉ tồn tại trong
  một request.
- Backend không giữ hội thoại: app gửi lại các lượt gần nhất mỗi lần.

### Sự kiện

| type              | Trường                          | Ý nghĩa                                                                                  |
| ----------------- | ------------------------------- | ---------------------------------------------------------------------------------------- |
| `tool`            | `name`                          | Một công cụ bắt đầu chạy (app hiện dòng trạng thái)                                      |
| `delta`           | `text`                          | Một đoạn văn bản của câu trả lời, nhiều dòng                                             |
| `navigate`        | `path`, `title`                 | App chuyển trang (từ `open_screen`); lượt kết thúc ngay sau đó                           |
| `action_required` | `action` (`PendingActionView`)  | Một thay đổi dữ liệu chờ xác nhận; lượt kết thúc ngay sau đó                             |
| `done`            | `answer`, `citations`           | Dòng cuối khi thành công; `answer` là văn bản cuối cùng (client thay văn bản đã nhận bằng nó) |
| `error`           | `messageCode`, `message`        | Dòng cuối khi lỗi giữa chừng                                                             |

`citations` có dạng `CitationView` của chat tài liệu (`n`, `documentId`, `fileName`, `page`, `lineStart`, `lineEnd`,
`snippet`) và rỗng trừ khi `search_documents` đã chạy trong lượt.

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
2. Gọi mô hình với các công cụ mà người dùng được dùng. Nếu mô hình gọi công cụ: kết quả công cụ đọc được đưa lại cho mô
   hình và vòng lặp tiếp tục (tối đa `max-steps` lần gọi). Công cụ không tồn tại hoặc tham số sai được trả lại cho mô hình
   dưới dạng văn bản.
3. `navigate` và `action_required` là kết thúc: backend ghi sự kiện, rồi `done` với một câu cố định, không gọi mô hình
   thêm.
4. Quá `max-steps` mà mô hình vẫn gọi công cụ: `done` với "Mình chưa xử lý được yêu cầu này. Bạn thử diễn đạt lại ngắn
   gọn hơn nhé."
5. Ollama lỗi giữa chừng: `error` `ASSISTANT-AI_UNAVAILABLE`. Lỗi khác: `error` `CORE-INTERNAL_ERROR` với thông báo chung.

Từ chối **trước** khi stream bắt đầu là envelope JSON `status: ERROR` (HTTP 200): `CORE-VALIDATION_FAILED` (request
không hợp lệ), `ASSISTANT-AI_UNAVAILABLE` (Ollama không trả lời trong 2 giây); 401 khi thiếu token.

## POST /api/v1/assistant/agent/actions/{actionId}/confirm

Chạy yêu cầu chờ xác nhận bằng quyền của chính người gọi, qua cùng service với màn hình tương ứng. Trả
`BaseResponse<ActionResultView>`:

```json
{ "actionId": "6f1c0a52-…", "tool": "update_profile", "message": "Đã cập nhật hồ sơ.", "data": { } }
```

`data` phụ thuộc công cụ. Mỗi yêu cầu chỉ chạy một lần: yêu cầu bị gỡ khỏi bộ nhớ trước khi chạy, nên bấm hai lần chỉ
chạy một lần. Lời từ chối của service (validation, SSO, đang phải đổi mật khẩu) giữ nguyên envelope của nó.

## POST /api/v1/assistant/agent/actions/{actionId}/cancel

Bỏ yêu cầu mà không chạy. Trả `BaseResponse<Void>`.

## Yêu cầu chờ xác nhận

Giữ trong bộ nhớ (không có bảng nào): hết hạn sau `action-ttl`, gắn với người đã gây ra nó, tối đa 20 yêu cầu mỗi người
(yêu cầu cũ nhất bị bỏ khi thêm cái thứ 21), và một tác vụ nền quét yêu cầu hết hạn mỗi phút. Khởi động lại backend làm
mất mọi yêu cầu; app báo yêu cầu đã hết hạn khi xác nhận.

## Mã lỗi

| messageCode                  | HTTP | Khi nào                                                                            |
| ---------------------------- | ---- | ---------------------------------------------------------------------------------- |
| `ASSISTANT-AI_UNAVAILABLE`   | 200  | Ollama không phản hồi (trước stream: envelope; giữa stream: sự kiện `error`)       |
| `ASSISTANT-ACTION_NOT_FOUND` | 200  | Yêu cầu không có, hết hạn, đã xác nhận/huỷ, hoặc của người khác (cùng một mã, id không tiết lộ gì) |
| `ASSISTANT-ACTION_STALE`     | 200  | Dữ liệu đã đổi từ lúc trợ lý đề xuất (khi xác nhận)                                |
| `CORE-VALIDATION_FAILED`     | 200  | Request `/stream` không hợp lệ                                                     |
| `CORE-INTERNAL_ERROR`        | —    | Lỗi bất ngờ giữa stream (sự kiện `error`, thông báo chung)                         |
