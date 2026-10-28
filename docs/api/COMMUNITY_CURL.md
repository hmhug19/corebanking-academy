# Hướng dẫn kiểm thử API Community

Có **138 route thiết kế, 135 route đang bật**. Tạm dừng C126–C128 (`/api/v1/community/ai/**`): không có trên
Swagger, gọi trả 404. Xem phạm vi, quyền và vòng đời trong [COMMUNITY.md](COMMUNITY.md).
Mục 1–6 minh họa luồng dùng ID/version từ response; mục 8 có cURL cho từng operation.
Schema request/response đầy đủ để import Postman/FE: [community-openapi.json](../postman/community-openapi.json).

Ví dụ dưới đây dùng Bash, `curl` và `jq`; PowerShell gọi `curl.exe` hoặc import request vào Postman.
Chỉ dùng tài khoản/dữ liệu dev/test đã được cho phép. Các lệnh không tự tạo tài khoản, reset mật khẩu hoặc cấp role Identity.
Không lưu token vào Git. `BASE_URL` trỏ đúng backend đang chạy; cổng mặc định repository là 8019.

```bash
export BASE_URL='http://localhost:8019'
export AUTHOR_TOKEN='<token người viết/người hỏi>'
export READER_TOKEN='<token một người khác cùng đơn vị>'
export MODERATOR_TOKEN='<token org moderator đã được cấp hợp lệ>'
export ADMIN_TOKEN='<token ADMIN đúng scope hoặc SUPER_ADMIN>'
```

Mọi JSON đều cần kiểm tra `status=SUCCESS`; HTTP 200 cũng có thể chứa lỗi nghiệp vụ.
Nếu version cũ, tải lại để xem thay đổi trước khi gửi yêu cầu mới; không tự retry bằng version mới để ghi đè.
Mỗi POST tạo mới dùng UUID mới; retry cùng thao tác phải giữ nguyên UUID và payload.

## 1. Danh mục tag và quyền moderator

Cấp moderator là thao tác quản trị tường minh, chỉ chạy khi có nhu cầu. Không tự cấp cho chính caller.
`ORG_ID`, `MODERATOR_USER_ID` là ID có thật tại môi trường test. Grant chưa có dùng expectedVersion=0;
nếu đã có và cần đổi trạng thái, lấy version trả về lần trước.

```bash
curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/moderation/moderators/$MODERATOR_USER_ID" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"orgId\":$ORG_ID,\"active\":true,\"expectedVersion\":0,\"reason\":\"Điều phối moderator cho kiểm thử Community\"}"

curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/tags" \
  -H "Authorization: Bearer $MODERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"slug":"spring-boot","name":"Spring Boot","description":"Thảo luận Spring Boot","aliases":["springboot"]}'

curl --fail-with-body -sS "$BASE_URL/api/v1/community/tags?q=Spring&page=0&size=20" \
  -H "Authorization: Bearer $AUTHOR_TOKEN"

curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/tags/$TAG_ID" \
  -H "Authorization: Bearer $MODERATOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"expectedVersion":0,"name":"Spring Boot","description":"Danh mục test","status":"active","aliases":["springboot"]}'
```

## 2. Tìm trước, tạo question và publish

Đặt `TAG_ID` bằng tagId từ bước trên. Luồng dưới lấy ID/version động từ response thành công.
Mẫu dùng `assets=[]` để kiểm thử nội dung văn bản; luồng file cần hoàn tất upload và chờ asset `ready` trước khi gắn.

```bash
curl --fail-with-body -sS --get "$BASE_URL/api/v1/community/questions/suggestions" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" --data-urlencode 'q=Oracle UCP connection pool' --data-urlencode 'limit=5'

QUESTION_REQUEST_ID="$(python -c 'import uuid; print(uuid.uuid4())')"
QUESTION_JSON="$(jq -nc --arg request "$QUESTION_REQUEST_ID" --argjson tag "$TAG_ID" \
  '{clientRequestId:$request,title:"Lỗi kết nối Oracle UCP khi hết connection",bodyMarkdown:"Môi trường test giả lập gặp timeout khi lấy connection. Cần kiểm tra những tham số nào?",visibility:"organization",tagIds:[$tag],context:{technologies:[{name:"Spring Boot",version:"3.x"}]},assets:[]}')"
QUESTION="$(curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/questions" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' -d "$QUESTION_JSON")"
test "$(jq -r '.status' <<< "$QUESTION")" = SUCCESS || { jq '{status,messageCode,message}' <<< "$QUESTION"; exit 1; }
QUESTION_ID="$(jq -r '.data.contentId' <<< "$QUESTION")"
PUBLISH_BODY="$(jq -c '.data | {expectedContentVersion:.contentVersion,expectedRevisionVersion:.draftRevision.revisionVersion}' <<< "$QUESTION")"
QUESTION="$(curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/contents/$QUESTION_ID/publish" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' -d "$PUBLISH_BODY")"
jq '{status,messageCode,data}' <<< "$QUESTION"

curl --fail-with-body -sS "$BASE_URL/api/v1/community/questions/$QUESTION_ID" -H "Authorization: Bearer $READER_TOKEN"
curl --fail-with-body -sS "$BASE_URL/api/v1/community/questions?page=0&size=20&resolutionStatus=unanswered&sort=lastActivityAt" \
  -H "Authorization: Bearer $READER_TOKEN"
```

Gửi lại **nguyên** `QUESTION_JSON` với cùng UUID: nhận cùng contentId, không thêm draft.
Đổi title nhưng giữ UUID: lỗi `COMMUNITY-IDEMPOTENCY_CONFLICT`.

## 3. Answer, vote, accept

Answer không nhận visibility/primaryGuildId/tagIds; quyền đọc kế thừa question ở từng request.

```bash
ANSWER_JSON="$(jq -nc --arg request "$(python -c 'import uuid; print(uuid.uuid4())')" \
  '{clientRequestId:$request,title:"Kiểm tra timeout và connection leak",bodyMarkdown:"Trong môi trường test, đối soát timeout, kích thước pool và việc đóng connection trong finally hoặc try-with-resources.",assets:[]}')"
ANSWER="$(curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/questions/$QUESTION_ID/answers" \
  -H "Authorization: Bearer $READER_TOKEN" -H 'Content-Type: application/json' -d "$ANSWER_JSON")"
test "$(jq -r '.status' <<< "$ANSWER")" = SUCCESS || { jq '{status,messageCode,message}' <<< "$ANSWER"; exit 1; }
ANSWER_ID="$(jq -r '.data.contentId' <<< "$ANSWER")"
PUBLISH_BODY="$(jq -c '.data | {expectedContentVersion:.contentVersion,expectedRevisionVersion:.draftRevision.revisionVersion}' <<< "$ANSWER")"
ANSWER="$(curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/contents/$ANSWER_ID/publish" \
  -H "Authorization: Bearer $READER_TOKEN" -H 'Content-Type: application/json' -d "$PUBLISH_BODY")"

curl --fail-with-body -sS "$BASE_URL/api/v1/community/questions/$QUESTION_ID/answers?page=0&size=20" \
  -H "Authorization: Bearer $AUTHOR_TOKEN"
curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/contents/$ANSWER_ID/vote" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' -d '{"value":1}'

QUESTION="$(curl --fail-with-body -sS "$BASE_URL/api/v1/community/questions/$QUESTION_ID" -H "Authorization: Bearer $AUTHOR_TOKEN")"
ACCEPT_BODY="$(jq -nc --argjson version "$(jq -r '.data.contentVersion' <<< "$QUESTION")" \
  --argjson answer "$ANSWER_ID" --argjson revision "$(jq -r '.data.publishedRevision.revisionId' <<< "$ANSWER")" \
  '{expectedContentVersion:$version,answerContentId:$answer,answerRevisionId:$revision}')"
curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/questions/$QUESTION_ID/accepted-answer" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' -d "$ACCEPT_BODY"
```

PUT vote cùng value hai lần vẫn một vote; value=0 bỏ vote. Tự vote trả `COMMUNITY-SELF_VOTE`.
Bỏ accept gửi hai trường `answerContentId:null,answerRevisionId:null` cùng version mới.

## 4. Nội dung, draft, lịch sử, tag, ACL và lifecycle

Những số version dưới đây là biến từ GET gần nhất, không dùng cố định 0 sau khi đã chỉnh sửa.

```bash
curl --fail-with-body -sS "$BASE_URL/api/v1/community/contents/$QUESTION_ID?includeOwnDraft=true" -H "Authorization: Bearer $AUTHOR_TOKEN"
curl --fail-with-body -sS "$BASE_URL/api/v1/community/me/contents?kind=question&page=0&size=20" -H "Authorization: Bearer $AUTHOR_TOKEN"

curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/contents/$QUESTION_ID/draft" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' -d "{\"expectedContentVersion\":$CONTENT_VERSION}"

curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/contents/$QUESTION_ID/draft" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedContentVersion\":$CONTENT_VERSION,\"expectedRevisionVersion\":$REVISION_VERSION,\"title\":\"Cập nhật câu hỏi UCP\",\"bodyMarkdown\":\"Nội dung bổ sung trong môi trường mô phỏng, không dùng log hoặc credential thật.\",\"context\":{},\"assets\":[]}"

curl --fail-with-body -sS "$BASE_URL/api/v1/community/contents/$QUESTION_ID/revisions?page=0&size=20" -H "Authorization: Bearer $AUTHOR_TOKEN"
curl --fail-with-body -sS "$BASE_URL/api/v1/community/contents/$QUESTION_ID/revisions/$REVISION_ID" -H "Authorization: Bearer $AUTHOR_TOKEN"

curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/contents/$QUESTION_ID/tags" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedContentVersion\":$CONTENT_VERSION,\"tagIds\":[$TAG_ID]}"

curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/contents/$QUESTION_ID/access" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedContentVersion\":$CONTENT_VERSION,\"visibility\":\"restricted\",\"primaryGuildId\":null,\"grants\":[{\"userId\":\"$READER_USER_ID\",\"permission\":\"reader\",\"expiresAt\":null}]}"

curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/contents/$QUESTION_ID/lifecycle" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedContentVersion\":$CONTENT_VERSION,\"action\":\"close\",\"reason\":\"Đã tổng hợp giải pháp cho tình huống test\"}"
```

Lifecycle nhận close/reopen cho question, archive/unarchive, soft_delete. Question có answer published của người khác
không được tự xóa. Xóa answer đang accept phải bỏ accept cùng transaction; restore qua moderation chưa triển khai.

## 5. Bài viết và comment

```bash
curl --fail-with-body -sS "$BASE_URL/api/v1/community/articles?q=Spring&page=0&size=20" -H "Authorization: Bearer $READER_TOKEN"
curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/articles" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"clientRequestId":"f2be484c-31b0-4c27-9fc4-3139976320c0","title":"Mẹo sử dụng try-with-resources","bodyMarkdown":"Ví dụ giả lập để chia sẻ cách quản lý vòng đời connection trong Java.","visibility":"organization","tagIds":[],"context":{},"assets":[]}'

curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/contents/$QUESTION_ID/comments" \
  -H "Authorization: Bearer $READER_TOKEN" -H 'Content-Type: application/json' \
  -d '{"clientRequestId":"017d49d5-39ea-48b0-8f6a-5a8af5a83c6a","parentCommentId":null,"reviewThreadId":null,"bodyMarkdown":"Bạn đã thử kiểm tra timeout trên môi trường test chưa?"}'
curl --fail-with-body -sS "$BASE_URL/api/v1/community/contents/$QUESTION_ID/comments?page=0&size=20" -H "Authorization: Bearer $AUTHOR_TOKEN"
curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/comments/$COMMENT_ID" \
  -H "Authorization: Bearer $READER_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":$COMMENT_VERSION,\"bodyMarkdown\":\"Bổ sung tình huống mô phỏng để đối soát timeout.\"}"
curl --fail-with-body -sS -X DELETE "$BASE_URL/api/v1/community/comments/$COMMENT_ID?expectedVersion=$COMMENT_VERSION" \
  -H "Authorization: Bearer $READER_TOKEN"
```

Comment chỉ được tạo/sửa/xóa khi parent active. Reply tối đa 3 cấp. Thực hiện phần này trước khi close question
ở bước 4, hoặc reopen bằng version mới trước khi kiểm thử comment.

## 6. Guild

```bash
curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/guilds" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d '{"clientRequestId":"6dc859a3-5536-42c8-8b3c-508b6aa010cc","slug":"backend-demo","name":"Backend Demo Guild","description":"Nhóm mô phỏng để test","visibility":"organization","joinPolicy":"approval"}'
curl --fail-with-body -sS "$BASE_URL/api/v1/community/guilds?mine=false&page=0&size=20" -H "Authorization: Bearer $READER_TOKEN"
curl --fail-with-body -sS "$BASE_URL/api/v1/community/guilds/$GUILD_ID" -H "Authorization: Bearer $AUTHOR_TOKEN"
curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/guilds/$GUILD_ID" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":$GUILD_VERSION,\"name\":\"Backend Demo Guild\",\"description\":\"Nhóm kiểm thử\",\"visibility\":\"organization\",\"joinPolicy\":\"approval\",\"status\":\"active\"}"
curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/guilds/$GUILD_ID/join" -H "Authorization: Bearer $READER_TOKEN"
curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/guilds/$GUILD_ID/members/$READER_USER_ID" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":$MEMBERSHIP_VERSION,\"action\":\"approve\"}"
curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/guilds/$GUILD_ID/invitations" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"userIds\":[\"$OTHER_USER_ID\"]}"
curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/guilds/$GUILD_ID/invitation-response" \
  -H "Authorization: Bearer $OTHER_USER_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":$INVITATION_VERSION,\"action\":\"accept\"}"
curl --fail-with-body -sS "$BASE_URL/api/v1/community/guilds/$GUILD_ID/members?page=0&size=20" -H "Authorization: Bearer $AUTHOR_TOKEN"
curl --fail-with-body -sS "$BASE_URL/api/v1/community/guilds/$GUILD_ID/feed?page=0&size=20" -H "Authorization: Bearer $READER_TOKEN"
curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/guilds/$GUILD_ID/leave" \
  -H "Authorization: Bearer $READER_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":$MEMBERSHIP_VERSION}"
curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/guilds/$GUILD_ID/transfer-owner" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' \
  -d "{\"expectedVersion\":$GUILD_VERSION,\"successorUserId\":\"$SUCCESSOR_USER_ID\"}"
```

Successor phải active member cùng org tại thời điểm transfer; không chọn người vừa leave. Owner không được tự rời
khi chưa transfer. Private Guild cần joinPolicy=invite; lời mời chưa accept không mở nội dung visibility=guild.

## 7. Kiểm chứng tự động

Kết quả cuối ngày 28/10/2026: `clean verify` **235 test, 0 failure/error, 0 skipped**, gồm 35 test Community.
49 test từng bị bỏ qua vì thiếu Docker đã được chạy lại với PostgreSQL 16.15 và pgvector 0.8.0 thật.
Schema được khởi tạo bằng file SQL nguyên bản, không thay vector bằng real[]. Swagger còn 135 operation đang bật,
C126–C128 vắng mặt và gọi trả 404. Ba unit test storage mới kiểm ETag đã ACK, manifest sai và lỗi provider.

Bộ HTTP thật riêng đã pass 6 nhóm media, 5 nhóm scheduler/worker và 1 ca hard crash/restart: MP4 21 MB qua 3 part,
retry không nhân byte, FFmpeg/ffprobe/faststart, HEAD/GET/Range, transcript, thu hồi ACL, fanout/digest, ledger reversal,
cleanup object thật và lease phục hồi cùng asset từ attempt 1 sang attempt 2. Dùng S3 SeaweedFS 4.51 local và
các tài khoản tổng hợp đã được phép. Các kiểm tra trước về Q&A/Guild/collection/search/role vẫn được giữ trong suite.
Chi tiết lỗi đã sửa và giới hạn ở [Community](COMMUNITY.md#kết-quả-kiểm-thử-tích-hợp-ngày-28102026).

```bash
# Docker/pgvector: tạo database tạm riêng, không sử dụng DB_URL của môi trường thật.
mvn -Dcommunity.api.integration=true -Dcommunity.vector.integration=true clean verify

# Mặc định không bật Community opt-in; không hiểu skipped là pass.
mvn clean verify
```

Không có Docker: suite hỗ trợ opt-in các DB tổng hợp đã được chuẩn bị thủ công trên localhost có **pgvector thật**:

- `community_api_check_20261027` cho Community; `knowledge_integration_check_20261028` cho Knowledge/Assistant.
- Chỉ dùng database test riêng vì suite có thao tác ghi/xóa fixture. Không dùng DB chung hoặc tunnel vào production.
- Khởi tạo theo thứ tự Identity → Learning → media/progress → Knowledge → Community; không thay kiểu vector/index.
- Không lấy connection/password từ `.env`, không tự tạo hoặc cấp lại quyền tài khoản thật để chạy test.

```bash
mvn -Dcommunity.api.integration=true -Dcommunity.api.local=true -Dcommunity.api.local.port=25449 \
  -Dknowledge.api.local=true -Dknowledge.api.local.port=25449 -Dcommunity.vector.integration=true clean verify
```

Lần chạy local dùng Java 17, Maven offline, `-DforkCount=0` và Byte Buddy agent có sẵn trong Maven cache do máy hạn chế
RAM. Windows cần đặt `-Djdk.net.unixdomain.tmpdir="<thư mục tmp có thật với dấu backslash Windows>"` nếu gặp
`Unable to establish loopback connection`. Đây là cấu hình test, không đổi runtime production.

**Giới hạn**: chưa chạy trên AIStor/TLS/network của production, chưa benchmark tải thực tế và không chạy inference
Ollama thành công vì 3 API AI đang tạm dừng. Nhánh model không sẵn có đã xác minh retry tối đa 3 lần và không chặn
fanout/ledger/digest. Không coi nhánh lỗi này là kiểm chứng model thành công. Timer retry/retention rút ngắn chỉ ở fixture.
Baseline chuẩn: [learning_hub_community.sql](../sql/learning_hub_community.sql).

### 7.1. Tải file, chờ kiểm tệp và đọc Range

Ví dụ dưới dùng PDF thật tối đa 8 MiB để vừa một part. File lớn hơn phải chia theo `partSize` response,
gửi từng part bắt đầu từ 1 và hash riêng từng part. Không đổi byte giữa initiate và complete.
Worker media phải bật theo [cấu hình vận hành](COMMUNITY.md#cài-đặt-và-vận-hành).

```bash
FILE='./tai-lieu.pdf'
BYTES=$(wc -c < "$FILE" | tr -d ' ')
SHA=$(sha256sum "$FILE" | cut -d ' ' -f 1)
REQUEST_ID=$(python -c 'import uuid; print(uuid.uuid4())')
INIT_BODY=$(jq -nc --arg id "$REQUEST_ID" --arg sha "$SHA" --argjson bytes "$BYTES" \
  '{requestId:$id,originalName:"tai-lieu.pdf",mimeType:"application/pdf",sizeBytes:$bytes,checksumSha256:$sha}')
UPLOAD=$(curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/assets/uploads" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' -d "$INIT_BODY")
test "$(jq -r '.status' <<< "$UPLOAD")" = SUCCESS || { jq '{status,messageCode,message}' <<< "$UPLOAD"; exit 1; }
UPLOAD_ID=$(jq -r '.data.uploadId' <<< "$UPLOAD")
ASSET_ID=$(jq -r '.data.assetId' <<< "$UPLOAD")
curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/assets/uploads/$UPLOAD_ID/parts/1" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/octet-stream' \
  -H "X-Part-SHA256: $SHA" --data-binary "@$FILE"
curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/assets/uploads/$UPLOAD_ID/complete" \
  -H "Authorization: Bearer $AUTHOR_TOKEN"
curl --fail-with-body -sS "$BASE_URL/api/v1/community/assets/uploads/$UPLOAD_ID" \
  -H "Authorization: Bearer $AUTHOR_TOKEN"
```

Chỉ gắn `ASSET_ID` vào draft khi status=`ready`; draft assets dùng `{assetId,usage,sortNo,caption?,altText?}`.
Lấy `revisionAssetId` từ revision đã lưu rồi kiểm GET/HEAD/Range dưới token được phép đọc nguồn:

```bash
curl --fail-with-body -sS --head "$BASE_URL/api/v1/community/revision-assets/$REVISION_ASSET_ID/content" \
  -H "Authorization: Bearer $READER_TOKEN"
curl --fail-with-body -sS "$BASE_URL/api/v1/community/revision-assets/$REVISION_ASSET_ID/content" \
  -H "Authorization: Bearer $READER_TOKEN" -H 'Range: bytes=0-1023' --output './sample-range.bin'
```

### 7.2. Transcript, Learning pin và AI

Transcript yêu cầu video `ready` do caller có quyền công bố trên các nguồn dùng asset; ví dụ đoạn 0–5 giây
chỉ dùng khi video dài ít nhất 5 giây. Lấy transcriptId/version từ response rồi publish C76.

```bash
TRANSCRIPT_REQUEST_ID=$(python -c 'import uuid; print(uuid.uuid4())')
TRANSCRIPT_BODY=$(jq -nc --arg id "$TRANSCRIPT_REQUEST_ID" \
  '{clientRequestId:$id,language:"vi",bodyText:"Giới thiệu luồng xử lý API.",segments:[{startSeconds:0,endSeconds:5,text:"Giới thiệu luồng xử lý API."}]}')
curl --fail-with-body -sS -X POST "$BASE_URL/api/v1/community/assets/$VIDEO_ASSET_ID/transcripts" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' -d "$TRANSCRIPT_BODY"

# Sau khi lưu draft Learning, dùng đúng revision/lesson ID hiện tại; bỏ lessonId để gắn cấp khóa học.
PIN_BODY=$(jq -nc --argjson version "$COURSE_REVISION_VERSION" --argjson revision "$CONTENT_REVISION_ID" \
  '{expectedCourseRevisionVersion:$version,items:[{contentRevisionId:$revision,sortNo:0}]}')
curl --fail-with-body -sS -X PUT "$BASE_URL/api/v1/community/course-revisions/$COURSE_REVISION_ID/resources" \
  -H "Authorization: Bearer $AUTHOR_TOKEN" -H 'Content-Type: application/json' -d "$PIN_BODY"

# C126–C128 đang tạm dừng; không gọi API AI trong bản hiện tại.
```

Đặc tả lưu để bật lại: AI cần index ready, pgvector và Ollama thật. `no_sources`, `failed`, `discarded` là trạng thái lượt AI;
không đổi chúng thành câu trả lời thành công trên UI. Sau feedback mới dùng expectedVersion=0; cập nhật dùng version trả về.
Mẫu cURL từng route bên dưới minh họa schema, không phải chuỗi workflow tự chạy: các ID, metadata từng kind và
version cần thay bằng dữ liệu hợp lệ như các bước trên.

## 8. cURL đầy đủ C01–C138

**135 API đang hoạt động. C126–C128 tạm dừng**, không đưa vào các lệnh gọi dưới đây.

Các lệnh dưới đây dùng **Bash/Git Bash/WSL**. Với PowerShell, dùng `curl.exe` và chuyển biến/continuation theo cú pháp PowerShell.

Thiết lập `BASE_URL`, `TOKEN` và các biến ID bằng kết quả tạo/đọc API. Mẫu ID/version không phải seed có sẵn. Lấy version hiện tại trước command; tạo UUID mới cho thao tác mới, giữ UUID khi retry cùng payload. Không chạy toàn bộ lệnh như một script trên production: có lệnh đổi quyền, xóa mềm, đóng và retire.

```bash
export BASE_URL="http://localhost:8019"
export TOKEN="<access_token>"
export CONTENT_ID=1
export GUILD_ID=1
export PART_FILE="./part-1.bin"
export PART_SHA256="<sha256-hex-64>"
```

Request JSON được lấy theo schema Swagger. Các ví dụ dùng tham chiếu minh họa; điều chỉnh danh sách người nhận, tag/asset đã tạo và metadata theo luồng nghiệp vụ trước khi gọi. `status=ERROR` vẫn có thể đi với HTTP 200.

Schema máy đọc được: [community-openapi.json](../postman/community-openapi.json).

### C01 — Danh sách câu hỏi

```bash
curl --request GET "${BASE_URL}/api/v1/community/questions" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C02 — Tìm câu hỏi trước khi đăng

```bash
curl --request GET "${BASE_URL}/api/v1/community/questions/suggestions?q=Du%20lieu%20mau" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C03 — Tạo câu hỏi

```bash
curl --request POST "${BASE_URL}/api/v1/community/questions" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": []}'
```

### C04 — Chi tiết câu hỏi

```bash
curl --request GET "${BASE_URL}/api/v1/community/questions/${QUESTION_ID}" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C05 — Danh sách câu trả lời

```bash
curl --request GET "${BASE_URL}/api/v1/community/questions/${QUESTION_ID}/answers" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C06 — Viết câu trả lời

```bash
curl --request POST "${BASE_URL}/api/v1/community/questions/${QUESTION_ID}/answers" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": []}'
```

### C07 — Xác nhận câu trả lời

```bash
curl --request PUT "${BASE_URL}/api/v1/community/questions/${QUESTION_ID}/accepted-answer" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0}'
```

### C08 — Danh sách bài viết

```bash
curl --request GET "${BASE_URL}/api/v1/community/articles" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C09 — Tạo bài viết

```bash
curl --request POST "${BASE_URL}/api/v1/community/articles" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": []}'
```

### C10 — Chi tiết nội dung

```bash
curl --request GET "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C11 — Nội dung của tôi

```bash
curl --request GET "${BASE_URL}/api/v1/community/me/contents" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C12 — Lấy hoặc tạo draft

```bash
curl --request POST "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/draft" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0}'
```

### C13 — Lưu draft

```bash
curl --request PUT "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/draft" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "expectedRevisionVersion": 0, "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "context": {}, "assets": []}'
```

### C14 — Xuất bản nội dung

```bash
curl --request POST "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/publish" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "expectedRevisionVersion": 0}'
```

### C15 — Lịch sử phiên bản

```bash
curl --request GET "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/revisions" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C16 — Chi tiết phiên bản

```bash
curl --request GET "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/revisions/${REVISION_ID}" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C17 — Thay đổi vòng đời nội dung

```bash
curl --request POST "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/lifecycle" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "action": "close", "reason": "Kiểm thử nghiệp vụ trên dữ liệu tổng hợp."}'
```

### C18 — Thay phạm vi truy cập

```bash
curl --request PUT "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/access" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "visibility": "organization", "grants": []}'
```

### C19 — Thay tag nội dung

```bash
curl --request PUT "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/tags" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "tagIds": []}'
```

### C20 — Bình chọn

```bash
curl --request PUT "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/vote" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{}'
```

### C21 — Danh mục tag

```bash
curl --request GET "${BASE_URL}/api/v1/community/tags" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C22 — Tạo tag

```bash
curl --request POST "${BASE_URL}/api/v1/community/tags" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"slug": "danh-muc-mau", "name": "Danh mục mẫu"}'
```

### C23 — Cập nhật tag

```bash
curl --request PUT "${BASE_URL}/api/v1/community/tags/${TAG_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "name": "Danh mục mẫu", "status": "active"}'
```

### C24 — Gộp tag trong cùng đơn vị

```bash
curl --request POST "${BASE_URL}/api/v1/community/tags/${TAG_ID}/merge" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "targetTagId": 1, "reason": "Kiểm thử nghiệp vụ trên dữ liệu tổng hợp."}'
```

### C25 — Danh sách bình luận

```bash
curl --request GET "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/comments" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C26 — Viết bình luận

```bash
curl --request POST "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/comments" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức."}'
```

### C27 — Sửa bình luận

```bash
curl --request PUT "${BASE_URL}/api/v1/community/comments/${COMMENT_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức."}'
```

### C28 — Xóa bình luận

```bash
curl --request DELETE "${BASE_URL}/api/v1/community/comments/${COMMENT_ID}?expectedVersion=0" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C29 — Danh sách đã lưu

```bash
curl --request GET "${BASE_URL}/api/v1/community/me/bookmarks" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C30 — Lưu hoặc bỏ lưu nội dung

```bash
curl --request PUT "${BASE_URL}/api/v1/community/me/bookmarks/${CONTENT_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{}'
```

### C31 — Danh sách đang theo dõi

```bash
curl --request GET "${BASE_URL}/api/v1/community/me/subscriptions" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C32 — Thiết lập theo dõi

```bash
curl --request PUT "${BASE_URL}/api/v1/community/me/subscriptions" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"mode": "instant"}'
```

### C33 — Xem thông báo cá nhân

```bash
curl --request GET "${BASE_URL}/api/v1/community/me/notifications" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C34 — Đánh dấu đã đọc thông báo

```bash
curl --request POST "${BASE_URL}/api/v1/community/me/notifications/${NOTIFICATION_ID}/read" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C35 — Mời chuyên gia hỗ trợ câu hỏi

```bash
curl --request POST "${BASE_URL}/api/v1/community/questions/${CONTENT_ID}/help-requests" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{}'
```

### C36 — Phản hồi lời mời hỗ trợ

```bash
curl --request PUT "${BASE_URL}/api/v1/community/help-requests/${HELP_REQUEST_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "action": "accept"}'
```

### C37 — Báo cáo nội dung

```bash
curl --request POST "${BASE_URL}/api/v1/community/reports" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"reasonCode": "Du lieu mau"}'
```

### C38 — Hàng đợi report trong đơn vị

```bash
curl --request GET "${BASE_URL}/api/v1/community/moderation/reports" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C39 — Giao case cho moderator

```bash
curl --request POST "${BASE_URL}/api/v1/community/moderation/reports/${REPORT_ID}/assign" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "moderatorUserId": "000004"}'
```

### C40 — Mở evidence của case

```bash
curl --request GET "${BASE_URL}/api/v1/community/moderation/reports/${REPORT_ID}/evidence" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C41 — Xử lý report theo version của case và target

```bash
curl --request POST "${BASE_URL}/api/v1/community/moderation/reports/${REPORT_ID}/actions" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedReportVersion": 0, "expectedTargetVersion": 0, "action": "hide", "reason": "Kiểm thử nghiệp vụ trên dữ liệu tổng hợp."}'
```

### C42 — Cấp hoặc thu hồi org moderator

```bash
curl --request PUT "${BASE_URL}/api/v1/community/moderation/moderators/${USER_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"orgId": 1, "reason": "Kiểm thử nghiệp vụ trên dữ liệu tổng hợp.", "expectedVersion": 0}'
```

### C43 — Khởi tạo tải tài nguyên cộng đồng

```bash
curl --request POST "${BASE_URL}/api/v1/community/assets/uploads" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"requestId": "00000000-0000-4000-8000-000000000001", "originalName": "tai-lieu-mau.pdf", "mimeType": "application/pdf", "sizeBytes": 1, "checksumSha256": "0000000000000000000000000000000000000000000000000000000000000000"}'
```

### C44 — Xem tiến độ tải tài nguyên

```bash
curl --request GET "${BASE_URL}/api/v1/community/assets/uploads/${UPLOAD_ID}" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C45 — Tải một phần tài nguyên

```bash
curl --request PUT "${BASE_URL}/api/v1/community/assets/uploads/${UPLOAD_ID}/parts/${PART_NUMBER}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/octet-stream" \
  --header "X-Part-SHA256: ${PART_SHA256}" \
  --data-binary "@${PART_FILE}"
```

### C46 — Hoàn tất manifest tải tài nguyên

```bash
curl --request POST "${BASE_URL}/api/v1/community/assets/uploads/${UPLOAD_ID}/complete" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C47 — Hủy phiên tải tài nguyên

```bash
curl --request DELETE "${BASE_URL}/api/v1/community/assets/uploads/${UPLOAD_ID}" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C48 — Đọc tệp của phiên bản nội dung

```bash
curl --request GET "${BASE_URL}/api/v1/community/revision-assets/${REVISION_ASSET_ID}/content" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C49 — Đọc metadata HTTP của tệp

```bash
curl --request HEAD "${BASE_URL}/api/v1/community/revision-assets/${REVISION_ASSET_ID}/content" \
  --header "Authorization: Bearer ${TOKEN}" \
  --head
```

### C50 — Danh sách Guild

```bash
curl --request GET "${BASE_URL}/api/v1/community/guilds" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C51 — Tạo Guild

```bash
curl --request POST "${BASE_URL}/api/v1/community/guilds" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "slug": "danh-muc-mau", "name": "Danh mục mẫu", "visibility": "organization", "joinPolicy": "open"}'
```

### C52 — Chi tiết Guild

```bash
curl --request GET "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C53 — Cập nhật Guild

```bash
curl --request PUT "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "name": "Danh mục mẫu", "visibility": "organization", "joinPolicy": "open", "status": "active"}'
```

### C54 — Tham gia Guild

```bash
curl --request POST "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/join" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C55 — Mời thành viên Guild

```bash
curl --request POST "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/invitations" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"userIds": ["000004"]}'
```

### C56 — Quản lý thành viên Guild

```bash
curl --request PUT "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/members/${USER_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "action": "approve"}'
```

### C57 — Phản hồi lời mời Guild

```bash
curl --request POST "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/invitation-response" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "action": "accept"}'
```

### C58 — Rời Guild

```bash
curl --request POST "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/leave" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0}'
```

### C59 — Chuyển owner Guild

```bash
curl --request POST "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/transfer-owner" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "successorUserId": "000004"}'
```

### C60 — Danh sách thành viên Guild

```bash
curl --request GET "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/members" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C61 — Feed của Guild

```bash
curl --request GET "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/feed" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C62 — Danh sách trang Wiki

```bash
curl --request GET "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/wiki/pages" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C63 — Tạo trang Wiki

```bash
curl --request POST "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/wiki/pages" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": [], "slug": "danh-muc-mau"}'
```

### C64 — Di chuyển trang Wiki

```bash
curl --request PUT "${BASE_URL}/api/v1/community/wiki/pages/${CONTENT_ID}/location" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "slug": "danh-muc-mau"}'
```

### C65 — Gửi duyệt Wiki hoặc Radar

```bash
curl --request POST "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/submit" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "expectedRevisionVersion": 0}'
```

### C66 — Phê duyệt hoặc yêu cầu sửa revision

```bash
curl --request POST "${BASE_URL}/api/v1/community/revisions/${REVISION_ID}/decisions" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "expectedRevisionVersion": 0, "decision": "approved"}'
```

### C67 — Xem quyết định phê duyệt

```bash
curl --request GET "${BASE_URL}/api/v1/community/revisions/${REVISION_ID}/approvals" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C68 — Ghi nhận rà soát nội dung

```bash
curl --request POST "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/maintenance-reviews" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "revisionId": 1, "outcome": "current"}'
```

### C69 — Danh sách cần rà soát

```bash
curl --request GET "${BASE_URL}/api/v1/community/maintenance/review-due" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C70 — Xem Tech Radar

```bash
curl --request GET "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/radar" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C71 — Đề xuất mục Tech Radar

```bash
curl --request POST "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/radar" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": []}'
```

### C72 — Tìm video chia sẻ kỹ thuật

```bash
curl --request GET "${BASE_URL}/api/v1/community/recordings" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C73 — Tạo bài chia sẻ video

```bash
curl --request POST "${BASE_URL}/api/v1/community/recordings" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": []}'
```

### C74 — Soạn bản chép lời video

```bash
curl --request POST "${BASE_URL}/api/v1/community/assets/${ASSET_ID}/transcripts" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "language": "vi", "bodyText": "Nội dung chép lời mẫu.", "segments": [{"startSeconds": 0, "endSeconds": 1, "text": "Du lieu mau"}]}'
```

### C75 — Sửa bản chép lời nháp

```bash
curl --request PUT "${BASE_URL}/api/v1/community/transcripts/${TRANSCRIPT_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "language": "vi", "bodyText": "Nội dung chép lời mẫu.", "segments": [{"startSeconds": 0, "endSeconds": 1, "text": "Du lieu mau"}]}'
```

### C76 — Xuất bản chép lời video

```bash
curl --request POST "${BASE_URL}/api/v1/community/transcripts/${TRANSCRIPT_ID}/publish" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0}'
```

### C77 — Đọc bản chép lời đã xuất bản

```bash
curl --request GET "${BASE_URL}/api/v1/community/revision-assets/${REVISION_ASSET_ID}/transcript" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C78 — Tìm đề nghị review kiến trúc

```bash
curl --request GET "${BASE_URL}/api/v1/community/reviews" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C79 — Tạo đề nghị review kiến trúc

```bash
curl --request POST "${BASE_URL}/api/v1/community/reviews" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": []}'
```

### C80 — Mời phản biện thiết kế

```bash
curl --request POST "${BASE_URL}/api/v1/community/reviews/${CONTENT_ID}/invitations" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{}'
```

### C81 — Phản hồi lời mời review

```bash
curl --request PUT "${BASE_URL}/api/v1/community/review-invitations/${INVITATION_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "action": "accept"}'
```

### C82 — Góp ý vào phiên bản thiết kế

```bash
curl --request POST "${BASE_URL}/api/v1/community/reviews/${CONTENT_ID}/threads" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "revisionId": 1, "anchor": {"type": "section", "section": "Thiết kế"}, "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức."}'
```

### C83 — Giải quyết hoặc mở lại góp ý

```bash
curl --request PUT "${BASE_URL}/api/v1/community/review-threads/${THREAD_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "action": "resolve", "note": "Du lieu mau"}'
```

### C84 — Chốt kết luận review

```bash
curl --request POST "${BASE_URL}/api/v1/community/reviews/${CONTENT_ID}/conclude" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "concludedRevisionId": 1, "conclusion": "Du lieu mau         "}'
```

### C85 — Đóng đề nghị review

```bash
curl --request POST "${BASE_URL}/api/v1/community/reviews/${CONTENT_ID}/close" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "reason": "Kiểm thử nghiệp vụ trên dữ liệu tổng hợp."}'
```

### C86 — Tạo vòng review tiếp theo

```bash
curl --request POST "${BASE_URL}/api/v1/community/reviews/${CONTENT_ID}/follow-up" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": [], "sourceRevisionId": 1}'
```

### C87 — Tìm người có chuyên môn

```bash
curl --request GET "${BASE_URL}/api/v1/community/experts" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C88 — Xem kỹ năng người dùng

```bash
curl --request GET "${BASE_URL}/api/v1/community/users/${USER_ID}/expertise" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C89 — Thay danh sách kỹ năng của tôi

```bash
curl --request PUT "${BASE_URL}/api/v1/community/me/expertise" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedPreferenceVersion": 0, "items": []}'
```

### C90 — Tìm yêu cầu chia sẻ kiến thức

```bash
curl --request GET "${BASE_URL}/api/v1/community/knowledge-requests" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C91 — Tạo yêu cầu chia sẻ kiến thức

```bash
curl --request POST "${BASE_URL}/api/v1/community/knowledge-requests" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": []}'
```

### C92 — Nhận hoặc hoàn thành yêu cầu kiến thức

```bash
curl --request POST "${BASE_URL}/api/v1/community/knowledge-requests/${CONTENT_ID}/actions" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "action": "claim"}'
```

### C93 — Tìm bài học kinh nghiệm

```bash
curl --request GET "${BASE_URL}/api/v1/community/lessons-learned" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C94 — Viết bài học kinh nghiệm

```bash
curl --request POST "${BASE_URL}/api/v1/community/lessons-learned" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "title": "Nội dung mẫu cho cộng đồng", "bodyMarkdown": "Nội dung mẫu dùng để kiểm thử chức năng chia sẻ kiến thức.", "visibility": "organization", "tagIds": [], "context": {}, "assets": []}'
```

### C95 — Xem các tài liệu liên quan

```bash
curl --request GET "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/links" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C96 — Cập nhật liên kết tri thức

```bash
curl --request PUT "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/links" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "links": []}'
```

### C97 — Tìm kiếm thống nhất kho tri thức

```bash
curl --request GET "${BASE_URL}/api/v1/community/search?q=Du%20lieu%20mau" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C98 — Xem bảng tin tri thức

```bash
curl --request GET "${BASE_URL}/api/v1/community/feed" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C99 — Đọc tùy chọn người dùng

```bash
curl --request GET "${BASE_URL}/api/v1/community/me/preferences" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C100 — Cập nhật lịch digest và thông báo

```bash
curl --request PUT "${BASE_URL}/api/v1/community/me/preferences" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "timeZone": "Asia/Ho_Chi_Minh", "digestFrequency": "off", "digestLocalTime": "Du lieu mau", "notificationPreferences": {}}'
```

### C101 — Hoàn tất onboarding và theo dõi chủ đề

```bash
curl --request POST "${BASE_URL}/api/v1/community/me/onboarding" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "tagIds": [], "guildIdsToFollow": []}'
```

### C102 — Danh sách bộ sưu tập

```bash
curl --request GET "${BASE_URL}/api/v1/community/collections" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C103 — Tạo bộ sưu tập

```bash
curl --request POST "${BASE_URL}/api/v1/community/collections" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"clientRequestId": "00000000-0000-4000-8000-000000000001", "name": "Danh mục mẫu", "visibility": "private", "grants": []}'
```

### C104 — Nội dung bộ sưu tập

```bash
curl --request GET "${BASE_URL}/api/v1/community/collections/${COLLECTION_ID}/items" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C105 — Đổi metadata hoặc ACL bộ sưu tập

```bash
curl --request PUT "${BASE_URL}/api/v1/community/collections/${COLLECTION_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "name": "Danh mục mẫu", "visibility": "private", "grants": [], "status": "active"}'
```

### C106 — Thêm, sửa hoặc bỏ item bộ sưu tập

```bash
curl --request PUT "${BASE_URL}/api/v1/community/collections/${COLLECTION_ID}/items/${CONTENT_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedCollectionVersion": 0}'
```

### C107 — Lưu trữ bộ sưu tập

```bash
curl --request DELETE "${BASE_URL}/api/v1/community/collections/${COLLECTION_ID}?expectedVersion=0" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C108 — Xem các kỳ bản tin cá nhân

```bash
curl --request GET "${BASE_URL}/api/v1/community/me/digests" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C109 — Đọc nội dung bản tin

```bash
curl --request GET "${BASE_URL}/api/v1/community/me/digests/${BATCH_ID}" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C110 — Đánh dấu đã đọc bản tin

```bash
curl --request POST "${BASE_URL}/api/v1/community/me/digests/${BATCH_ID}/read" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C111 — Xem lịch sử đóng góp của tôi

```bash
curl --request GET "${BASE_URL}/api/v1/community/me/contributions" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C112 — Xem bảng đóng góp cộng đồng

```bash
curl --request GET "${BASE_URL}/api/v1/community/leaderboard" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C113 — Xem danh mục huy hiệu

```bash
curl --request GET "${BASE_URL}/api/v1/community/badges" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C114 — Định nghĩa luật huy hiệu

```bash
curl --request POST "${BASE_URL}/api/v1/community/badges" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"code": "DEMO_001", "name": "Danh mục mẫu", "ruleVersion": 1, "rule": {"metric": "accepted_answers", "threshold": 1}}'
```

### C115 — Ngừng trao huy hiệu theo luật cũ

```bash
curl --request POST "${BASE_URL}/api/v1/community/badges/${BADGE_ID}/retire" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0, "reason": "Kiểm thử nghiệp vụ trên dữ liệu tổng hợp."}'
```

### C116 — Xem huy hiệu còn đủ điều kiện

```bash
curl --request GET "${BASE_URL}/api/v1/community/users/${USER_ID}/badges" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C117 — Đối soát đóng góp cộng đồng

```bash
curl --request POST "${BASE_URL}/api/v1/community/moderation/contributions/reconcile" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"requestId": "00000000-0000-4000-8000-000000000001"}'
```

### C118 — Thống kê chia sẻ kiến thức

```bash
curl --request GET "${BASE_URL}/api/v1/community/analytics/overview" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C119 — Thống kê câu hỏi chưa được trả lời

```bash
curl --request GET "${BASE_URL}/api/v1/community/analytics/unanswered" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C120 — Thống kê nội dung cần cập nhật

```bash
curl --request GET "${BASE_URL}/api/v1/community/analytics/stale" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C121 — Thống kê hoạt động theo thời gian

```bash
curl --request GET "${BASE_URL}/api/v1/community/analytics/activity" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C122 — Ghi nhận lượt đọc nội dung

```bash
curl --request POST "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/view" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C123 — Đọc tài liệu tham khảo khóa học

```bash
curl --request GET "${BASE_URL}/api/v1/community/course-revisions/${COURSE_REVISION_ID}/resources" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C124 — Ghim tài liệu Community vào khóa nháp

```bash
curl --request PUT "${BASE_URL}/api/v1/community/course-revisions/${COURSE_REVISION_ID}/resources" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedCourseRevisionVersion": 0, "items": []}'
```

### C125 — Tìm nội dung có thể ghim vào khóa học

```bash
curl --request GET "${BASE_URL}/api/v1/community/learning/reference-candidates" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C129 — Yêu cầu lập lại chỉ mục tri thức

```bash
curl --request POST "${BASE_URL}/api/v1/community/moderation/search/reindex" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"requestId": "00000000-0000-4000-8000-000000000001"}'
```

### C130 — Xem tiến độ công việc cộng đồng

```bash
curl --request GET "${BASE_URL}/api/v1/community/jobs/${EVENT_ID}" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C131 — Lịch sử rà soát độ mới

```bash
curl --request GET "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/maintenance-reviews" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C132 — Cập nhật lịch rà soát Wiki

```bash
curl --request PUT "${BASE_URL}/api/v1/community/wiki/pages/${CONTENT_ID}/review-schedule" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0}'
```

### C133 — Ghim hoặc bỏ ghim nội dung trong Guild

```bash
curl --request PUT "${BASE_URL}/api/v1/community/guilds/${GUILD_ID}/pins/${CONTENT_ID}" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{}'
```

### C134 — Hàng đợi revision chờ duyệt

```bash
curl --request GET "${BASE_URL}/api/v1/community/moderation/revision-queue" \
  --header "Authorization: Bearer ${TOKEN}"
```

### C135 — Chuyển người duy trì nội dung

```bash
curl --request PUT "${BASE_URL}/api/v1/community/contents/${CONTENT_ID}/maintainer" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "maintainerUserId": "000004"}'
```

### C136 — Đánh dấu hoặc bỏ đánh dấu câu hỏi trùng

```bash
curl --request PUT "${BASE_URL}/api/v1/community/questions/${QUESTION_ID}/duplicate" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedContentVersion": 0, "reason": "Kiểm thử nghiệp vụ trên dữ liệu tổng hợp."}'
```

### C137 — Yêu cầu thử lại bản tin lỗi

```bash
curl --request POST "${BASE_URL}/api/v1/community/me/digests/${BATCH_ID}/retry" \
  --header "Authorization: Bearer ${TOKEN}" \
  --header "Content-Type: application/json" \
  --data-raw '{"expectedVersion": 0}'
```

### C138 — Xem các phiên tải tài nguyên của tôi

```bash
curl --request GET "${BASE_URL}/api/v1/community/me/assets" \
  --header "Authorization: Bearer ${TOKEN}"
```

