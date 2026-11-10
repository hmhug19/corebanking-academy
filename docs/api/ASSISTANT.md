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
| `app.assistant.agent.history-messages` | `20`     | Số tin nhắn tối đa trong request (10 lượt)                       |
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

`data` phụ thuộc công cụ. Mỗi yêu cầu chỉ chạy một lần: yêu cầu bị gỡ khỏi bộ nhớ trước khi chạy, nên bấm hai lần chỉ
chạy một lần. Lời từ chối của service (validation, SSO, đang phải đổi mật khẩu) giữ nguyên envelope của nó.

## POST /api/v1/assistant/agent/actions/{actionId}/cancel

Bỏ yêu cầu mà không chạy. Trả `BaseResponse<Void>`.

## Công cụ

| Công cụ            | Loại                         | Ai được dùng                                  | Kết quả                       |
| ------------------ | ---------------------------- | --------------------------------------------- | ----------------------------- |
| `search_documents` | đọc                          | mọi người dùng                                | văn bản cho mô hình + citations |
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

Giữ trong bộ nhớ (không có bảng nào): hết hạn sau `action-ttl`, gắn với người đã gây ra nó, tối đa 20 yêu cầu mỗi người
(yêu cầu cũ nhất bị bỏ khi thêm cái thứ 21), và một tác vụ nền quét yêu cầu hết hạn mỗi phút. Khởi động lại backend làm
mất mọi yêu cầu; app báo yêu cầu đã hết hạn khi xác nhận.

## Mã lỗi

| messageCode                  | HTTP | Khi nào                                                                            |
| ---------------------------- | ---- | ---------------------------------------------------------------------------------- |
| `ASSISTANT-AI_UNAVAILABLE`   | 200  | Ollama không phản hồi (trước stream: envelope; giữa stream: sự kiện `error`)       |
| `ASSISTANT-ACTION_NOT_FOUND` | 200  | Yêu cầu không có, hết hạn, đã xác nhận/huỷ, hoặc của người khác (cùng một mã, id không tiết lộ gì) |
| `ASSISTANT-BUSY`             | —    | Người dùng đang có một lượt chạy, hoặc server đã đủ lượt (sự kiện `error`)        |
| `ASSISTANT-RATE_LIMITED`     | —    | Người dùng đã hỏi quá `questions-per-hour` câu trong 1 giờ trượt (sự kiện `error`, kèm "thử lại sau X phút") |
| `ASSISTANT-ACTION_STALE`     | 200  | Dữ liệu đã đổi từ lúc trợ lý đề xuất (khi xác nhận)                                |
| `CORE-VALIDATION_FAILED`     | 200  | Request `/stream` không hợp lệ                                                     |
| `CORE-ACCESS_DENIED`         | 403  | Người gọi không có chức năng `AI_ASSISTANT` (mọi API của trợ lý nhanh)              |
| `CORE-INTERNAL_ERROR`        | —    | Lỗi bất ngờ giữa stream (sự kiện `error`, thông báo chung)                         |

## Giới hạn đã biết

- Câu trả lời của agent không có `[n]` mà câu đầu tiên nói bằng lời khác rằng tài liệu không có ("không được đề cập", "không
  tìm thấy", "không có thông tin"…) được coi là câu không tìm thấy: `done.answer` là câu cố định và không có nguồn. Câu có `[n]`
  vẫn giữ nguồn. Câu trả lời không có `[n]` hợp lệ nào cũng không có nguồn. Hai quy tắc này chỉ áp cho agent;
  `/knowledge/chat` giữ quy tắc cũ (không có `[n]` hợp lệ thì trả mọi đoạn).
- Khi `open_screen` từ chối, mô hình đôi khi nhắc lại mã chức năng thay vì tên màn hình. Không có gì được mở.
