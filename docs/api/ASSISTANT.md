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
- `skill` (tùy chọn): `qa_tai_lieu`, `dieu_huong`, `ho_so` hoặc `tro_chuyen`. Có `skill` hợp lệ thì server dùng luôn,
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

`data` phụ thuộc công cụ. Mỗi yêu cầu chỉ chạy một lần: yêu cầu được đánh dấu đã xác nhận bằng một câu lệnh UPDATE có điều kiện trước khi chạy, nên bấm hai lần (kể cả
từ hai instance) chỉ chạy một lần. Lời từ chối của service (validation, SSO, đang phải đổi mật khẩu) giữ nguyên envelope của nó.

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
