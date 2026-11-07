# API Quiz Engine (Assessment)

Phạm vi P0: danh mục năng lực, ngân hàng câu hỏi, cấu hình bài thi và luồng làm bài của học viên (bắt đầu/tiếp
tục, lưu đáp án, nộp, chấm, xem kết quả) cùng job tự nộp khi hết giờ. P1: quản trị lượt thi (tra cứu, chi tiết,
hủy lượt, nộp hộ), cấp/thu hồi lượt thêm, đổi trạng thái/xóa câu hỏi hàng loạt, đóng/xóa/xem thử đề, xóa nhóm
năng lực, lịch sử và xuất CSV lượt thi của học viên, nhập/xuất Excel câu hỏi, báo cáo thi và chấm lại. Gắn bài thi
vào điều kiện hoàn thành khóa học qua bài học quiz bắt buộc và `finalQuizId` của phiên bản khóa học.

Envelope, mã lỗi chung và phân trang theo [API_CONTRACT.md](API_CONTRACT.md). Mọi API yêu cầu đăng nhập.

## Dữ liệu

[learning_hub_assessment.sql](../sql/learning_hub_assessment.sql): 10 bảng `competencies`, `questions`,
`question_options`, `quizzes`, `quiz_questions`, `quiz_attempts`, `attempt_questions`, `attempt_answers`,
`quiz_attempt_grants`, `attempt_regrades`. Không FK; service kiểm tra tham chiếu. Thiết kế dựa trên `assessment_schema.sql` và điều
chỉnh theo quy ước repository:

| Điểm điều chỉnh | Lý do |
|---|---|
| Schema `learning_hub`, tên bảng số nhiều, PK `<entity>_id`, cột `create_time/update_time/row_version` | Thống nhất với Identity/Learning |
| `user_id`, `created_by`, `granted_by`… là `varchar(20)` | `users.user_id` là mã cán bộ dạng chuỗi |
| `quiz_attempts.enrollment_id` thay `assignment_id` | Quyền thi xét theo lần học (`course_enrollments`) của Learning |
| `questions.content_version` thay `version` | Tránh nhầm với `row_version` (optimistic lock) |
| `quizzes.row_version` | Chống hai người sửa cấu hình/pool cùng lúc |
| `quiz_attempts.grace_seconds` (snapshot) | Job tự nộp dùng ân hạn của chính lượt, không đọc cấu hình đề hiện tại |
| `quiz_questions.quiz_question_id` | PK đơn cho JPA; vẫn UNIQUE (quiz_id, question_id) |
| Không tạo 3 view báo cáo | Báo cáo chưa thuộc P0; tổng hợp lượt tính trong service |

Mã trạng thái/loại dùng IN HOA (`ACTIVE`, `DRAFT`, `IN_PROGRESS`…) theo thiết kế gốc.

## Quyền

| Nhóm API | Điều kiện |
|---|---|
| Đọc nhóm năng lực | Tài khoản đã đăng nhập |
| Ghi nhóm năng lực | SUPER_ADMIN hoặc ADMIN phạm vi GLOBAL |
| Ngân hàng chương trình | SUPER_ADMIN/GLOBAL hoặc ADMIN/INSTRUCTOR với GLOBAL/ORG bao phủ đơn vị sở hữu chương trình; không suy quyền ghi câu chỉ có `programId` từ một khóa OWN/TEAM |
| Thêm câu qua khóa | `courseId` (kèm `programId` hoặc không) trên POST `/questions` và nhập Excel: caller quản lý khóa (mọi scope, kể cả OWN/TEAM); câu vào ngân hàng chương trình của revision mới nhất, giữ `courseId` làm khóa nguồn. `programId` gửi kèm phải đúng chương trình đó |
| Chọn câu hỏi cho đề | `programId` + `courseId`: caller quản lý khóa, chương trình phải là chương trình của revision mới nhất và cùng đơn vị sở hữu; cho phép đọc câu hỏi dùng chung để lập đề |
| Câu theo ID/bulk, bài thi | Câu có `courseId`: người quản lý khóa nguồn hoặc người quản lý chương trình sở hữu. Câu không có `courseId`: quyền chương trình thực tế của câu. Bài thi: quyền quản lý khóa theo `LearningAccessPolicy` |
| `/me/**` | Vai trò LEARNER/OWN và lần học hợp lệ của khóa (khóa active, nguồn giao/tự đăng ký còn hiệu lực); lượt của người khác trả `ASSESSMENT-NOT_FOUND` |

Module Assessment truy cập Learning qua adapter công khai `LearningAssessmentAccess`, không dùng repository Learning.

## Danh sách API

| STT | Method / đường dẫn | Mục đích |
|---|---|---|
| 1 | GET `/api/v1/competencies?status` | Danh sách nhóm năng lực (phẳng, có parentId) |
| 2 | POST `/api/v1/competencies` | Tạo nhóm năng lực |
| 3 | PUT `/api/v1/competencies/{id}?expectedVersion` | Cập nhật nhóm năng lực |
| 4 | GET `/api/v1/questions?programId&courseId&q&lessonId&competencyId&questionType&difficulty&status&page&size&sort` | Câu hỏi trong chương trình (courseId riêng lẻ giữ tương thích cũ); sort `id/code/updateTime` |
| 5 | GET `/api/v1/questions/{id}` | Chi tiết câu, phương án, đáp án đúng, đề đang dùng |
| 6 | POST `/api/v1/questions` | Tạo câu hỏi |
| 7 | PUT `/api/v1/questions/{id}?expectedVersion` | Cập nhật câu hỏi và toàn bộ phương án |
| 8 | GET `/api/v1/quizzes?courseId&q&status&quizType&page&size&sort` | Danh sách đề của khóa kèm số lượt chấm/đạt; sort `id/title/updateTime` |
| 9 | GET `/api/v1/quizzes/{id}` | Cấu hình đề và pool câu hỏi |
| 10 | POST `/api/v1/quizzes` | Tạo đề DRAFT |
| 11 | PUT `/api/v1/quizzes/{id}?expectedVersion` | Cập nhật cấu hình đề |
| 12 | PUT `/api/v1/quizzes/{id}/questions?expectedVersion` | Thay pool câu hỏi |
| 13 | POST `/api/v1/quizzes/{id}/publish` | Xuất bản (body `expectedVersion`) |
| 14 | GET `/api/v1/me/quizzes/{quizId}` | Màn giới thiệu: cấu hình, lượt còn lại, lượt dở, lý do chặn |
| 15 | POST `/api/v1/me/quizzes/{quizId}/attempts` | Bắt đầu hoặc tiếp tục lượt |
| 16 | GET `/api/v1/me/quizzes/{quizId}/attempts` | Lịch sử thi của tôi theo đề |
| 17 | GET `/api/v1/me/quiz-attempts/{attemptId}` | Tải lại lượt đang làm (F5/đổi máy) |
| 18 | PATCH `/api/v1/me/quiz-attempts/{attemptId}/questions/{attemptQuestionId}` | Lưu đáp án/cờ xem lại một câu |
| 19 | POST `/api/v1/me/quiz-attempts/{attemptId}/submit` | Nộp bài, chấm, trả kết quả |
| 20 | GET `/api/v1/me/quiz-attempts/{attemptId}/result` | Kết quả lượt đã chấm |
| 21 | POST `/api/v1/questions/bulk-status` | Đổi ACTIVE/INACTIVE tối đa 100 câu |
| 22 | POST `/api/v1/questions/bulk-delete` | Xóa mềm tối đa 100 câu không nằm trong đề nào |
| 23 | GET `/api/v1/quizzes/{quizId}/attempts?userId&status&passed&from&to&page&size&sort` | Lượt thi của đề cho quản trị; sort `id/startedAt/submittedAt/scorePercent` |
| 24 | GET `/api/v1/quiz-attempts/{attemptId}` | Chi tiết bài làm, đáp án đúng, IP/thiết bị, số lần đổi đáp án |
| 25 | POST `/api/v1/quiz-attempts/{attemptId}/void` | Hủy lượt (body `reason`) |
| 26 | GET `/api/v1/quizzes/{quizId}/grants?userId` | Lịch sử cấp thêm lượt, gồm lần đã thu hồi |
| 27 | POST `/api/v1/quizzes/{quizId}/grants` | Cấp thêm lượt (`userId`, `extraAttempts` 1–10, `reason`) |
| 28 | POST `/api/v1/quiz-attempt-grants/{grantId}/revoke` | Thu hồi lượt cấp nhầm |

| 29 | DELETE `/api/v1/competencies/{id}?expectedVersion` | Xóa mềm nhóm năng lực không còn câu hỏi/nhóm con gắn vào |
| 30 | POST `/api/v1/quizzes/{id}/close` | Đóng đề (body `expectedVersion`), tự nộp lượt đang làm |
| 31 | DELETE `/api/v1/quizzes/{id}?expectedVersion` | Xóa mềm đề chưa từng có lượt thi |
| 32 | GET `/api/v1/quizzes/{id}/preview` | Bốc thử một đề kèm đáp án; không tạo lượt |
| 33 | POST `/api/v1/quiz-attempts/{attemptId}/force-submit` | Quản trị nộp hộ lượt đang làm |
| 34 | GET `/api/v1/me/quiz-attempts?quizId&status&graded&q&passed&from&to&page&size&sort` | Mọi lượt thi của tôi; `q` tìm tên bài, `passed` lọc kết quả; `from` bao gồm và `to` loại trừ theo thời điểm nộp (ISO-8601); sort `id/startedAt/submittedAt`; `graded=true` chỉ lấy lượt đã chấm |
| 35 | GET `/api/v1/me/quiz-attempts/export?quizId&status&graded&sort` | Xuất CSV lượt thi của tôi, tối đa 10.000 dòng; cùng bộ lọc với danh sách |

| 36 | GET `/api/v1/questions/import-template` | Tải mẫu Excel nhập câu hỏi |
| 37 | POST `/api/v1/questions/import?programId&dryRun&overwrite` | Kiểm tra (dryRun=true, mặc định) hoặc nhập .xlsx vào chương trình; courseId cũ vẫn tương thích (part `file`) |
| 38 | GET `/api/v1/questions/export?courseId&q&competencyId&questionType&difficulty&status` | Xuất ngân hàng câu hỏi ra .xlsx cùng định dạng tệp nhập |
| 39 | POST `/api/v1/quizzes/{id}/regrade` | Chấm lại sau khi sửa đáp án (`questionId`, `reason`, `dryRun` mặc định true) |
| 40 | GET `/api/v1/reports/assessment/overview` | Tổng quan: lượt chấm, học viên, đạt, tỷ lệ đạt, điểm TB; theo đề và theo đơn vị |
| 41 | GET `/api/v1/reports/assessment/results` | Từng lượt thi kèm tên học viên, đơn vị/nhóm lúc thi; sort `id/startedAt/submittedAt/scorePercent` |
| 42 | GET `/api/v1/reports/assessment/learners?quizId` | Học viên được giao theo đề, gồm cả người chưa thi; sort `userId/finalScore/lastSubmittedAt` |
| 43 | GET `/api/v1/reports/assessment/competency?groupBy=user\|org` | Năng lực theo học viên hoặc đơn vị lúc thi |
| 44 | GET `/api/v1/reports/assessment/questions` | Phân tích câu hỏi theo phiên bản, câu sai nhiều nhất trước |
| 45 | GET `/api/v1/reports/assessment/{type}/export` | CSV cho `results/learners/competency/questions`, tối đa 10.000 dòng |
| 46 | GET `/api/v1/me/competency?courseId` | Năng lực của chính học viên |
| 47 | GET `/api/v1/me/courses/{courseId}/quizzes` | Đề đã xuất bản của khóa (FINAL_EXAM cuối) kèm lượt còn lại, lý do chặn và `lessonId` của bài quiz |

API 21–28, 30–33, 37–39 yêu cầu quyền quản lý khóa chứa câu hỏi/đề; API 36 cần vai trò giảng viên/quản trị; bulk cần
quyền trên mọi khóa trong lô. API 29 chỉ dành cho quản trị GLOBAL; API 34–35, 46 chỉ trả dữ liệu của caller. API 40–45
theo phạm vi báo cáo bên dưới. Báo cáo nhận chung `orgId` (gồm đơn vị con), `teamId`, `courseId`, `quizId`, `from`/`to`
(thời điểm nộp, ISO-8601; learners không áp dụng from/to).

`graded=true` của API 34–35 giữ lại lượt SUBMITTED và AUTO_SUBMITTED, bỏ IN_PROGRESS và VOIDED vốn chưa có điểm; kết hợp
với `quizId` và `status` theo AND (ví dụ `status=VOIDED&graded=true` trả rỗng). `graded=false` hoặc bỏ qua thì không lọc.

`expectedVersion` là `rowVersion` vừa đọc; lệch trả `ASSESSMENT-CONFLICT`.

## Quy tắc chính

**Ngân hàng chương trình.** Câu hỏi dùng chung gửi `programId`, không cần `courseId` hoặc `lessonId`. Người quản lý
khóa (ví dụ giảng viên OWN) thêm câu vào cùng ngân hàng bằng `courseId`, qua form hoặc nhập Excel; đây là đường
được hỗ trợ, không phải tương thích cũ.
`programId` trong summary/detail là chương trình sở hữu, không được chuyển khi sửa. ID danh mục lấy từ
`GET /api/v1/training-catalog/programs?ownerOrgId&page&size` như form khóa học. `GET /questions?programId=...`
trả toàn bộ câu hỏi trong chương trình qua phân trang, gồm câu tạo từ các khóa khác và câu không gắn khóa.
Lập đề gửi thêm `courseId` để server kiểm đúng quyền khóa và chương trình; không lấy scope từ request.
Thay pool chỉ nhận câu cùng chương trình của revision mới nhất của khóa. Giữ snapshot của các lượt thi cũ.
Mã câu hỏi vẫn duy nhất toàn ngân hàng theo quy tắc cũ, không thêm unique constraint mới.

```json
{"programId":2,"competencyId":1,"code":"CT-001","questionType":"SINGLE","content":"Nội dung câu hỏi?","explanation":"Giải thích","difficulty":"EASY","points":1,"status":"ACTIVE","options":[{"content":"A","correct":true},{"content":"B","correct":false}]}
```

`POST /questions` trả detail: `id`, `code`, `programId`, `courseId: null`, `lessonId: null`, các trường nội dung,
`contentVersion`, `options[{id,content,correct,sortNo}]`, `usedInQuizzes`, `rowVersion`, `createTime`, `updateTime`.
`PUT /questions/{id}?expectedVersion=N` nhận cùng body; giữ ID phương án cũ, bỏ ID khi thêm phương án.
Summary có `contentPreview`, `competencyName`, `optionCount`, `correctCount`, `usedInQuizCount` và `rowVersion`;
không trả đáp án. Tất cả field JSON là camelCase. Response vẫn nằm trong envelope chung.
Ngưng sử dụng bằng PUT hoặc bulk-status đều bị từ chối nếu làm đề đã xuất bản thiếu câu ACTIVE; rollback toàn bộ.

DB đã chạy baseline cần [upgrade ngân hàng chương trình](../sql/upgrade/2026-11-04-program-question-bank.sql)
trước backend mới. Backfill theo revision mới nhất, không đổi programme đã có; câu cũ chưa xác định được
chương trình vẫn giữ lại qua API courseId. Nhập/xuất Excel bằng courseId giữ nguyên contract. Câu có `courseId`
được người quản lý khóa đó hoặc người quản lý chương trình sở hữu sửa/xóa; câu không có `courseId` luôn kiểm theo
chương trình sở hữu.

**Nhập Excel theo chương trình.** Chọn chương trình ở FE rồi gửi `programId` trong query của
`POST /api/v1/questions/import`, multipart part `file`; không điền chương trình/khóa trong Excel.
Không cần chương trình có khóa học. Quyền ghi ngân hàng chương trình giống API tạo câu hỏi;
`courseId` chỉ dùng cho client cũ. Nếu gửi cả hai ID, khóa phải thuộc đúng chương trình đã chọn.
Mặc định `dryRun=true` chỉ kiểm tra; sau kiểm tra FE gửi lại chính file và tham số với `dryRun=false`.
Response data: `{"dryRun":true,"rowCount":2,"toCreate":2,"toUpdate":0,"created":0,"updated":0}`.
Nhập thành công trả `created/updated` thực tế và `dryRun=false`. Lỗi trả envelope
`ASSESSMENT-IMPORT_INVALID` với `errors[].field` như `Questions[3].dap_an_dung`; không ghi cả lô.
Tối đa 1.000 dòng, 5 MiB. Mã câu hỏi vẫn duy nhất toàn hệ thống; `overwrite=true` chỉ cập nhật
mã cùng chương trình, giữ ID câu hỏi và snapshot lượt thi cũ; không ghi đè ngân hàng khác.
Mẫu `GET /questions/import-template` gồm `Instructions` (hướng dẫn/ví dụ) và `Questions` trống.
Mười cột: `ma,noi_dung,loai,phuong_an,dap_an_dung,giai_thich,do_kho,nhom_kien_thuc,diem,trang_thai`.
Loại/độ khó/trạng thái có dropdown. Các phương án và đáp án dùng ký tự `|`; nhóm năng lực dùng mã,
không dùng ID/tên. Mẫu đã định dạng tối đa dòng 1001; bỏ qua các dòng dữ liệu trống.

**Câu hỏi.** Từ 2 tới 10 phương án; SINGLE đúng 1 đáp án đúng, MULTIPLE ít nhất 2. Mã để trống thì sinh `Q<ID>`.
PUT gửi danh sách phương án đầy đủ: có `id` thì sửa tại chỗ, không `id` là phương án mới, phương án cũ vắng mặt
bị xóa mềm (ID đã chụp vào đề không bị tái sử dụng). Câu đã từng phát đề mà đổi nội dung/giải thích/loại/phương
án/đáp án thì `contentVersion` tăng; lượt cũ **không** tự chấm lại.

**Đề.** Tạo ở DRAFT; mỗi khóa tối đa một FINAL_EXAM. Pool tối đa 2000 câu (`MAX_POOL_SIZE`), đủ đưa cả ngân hàng
chương trình vào đề RANDOM_POOL. Xuất bản khi số câu ACTIVE trong pool ≥ `questionCount` và
`closeAt` (nếu có) ở tương lai. Đề đã xuất bản vẫn sửa được nhưng phải còn đủ câu ACTIVE; thay đổi chỉ áp dụng cho
lượt mới vì lượt đã chụp `timeLimitMinutes`, `passPercent`, `scoringMode`, `graceSeconds`, `maxScore` và đề.

**Bắt đầu lượt.** Khóa advisory theo (đề, học viên) và unique index `IN_PROGRESS` chặn tạo hai lượt. Lượt dở còn
hạn được trả lại; lượt dở đã quá hạn được tự nộp trước. Thứ tự chặn (`blockReason`): `CLOSED`, `NOT_OPEN`,
`NOT_READY`, `LESSONS_REQUIRED`, `PASSED` (khi không cho thi nâng điểm), `NO_ATTEMPTS`, `WAIT`. Lượt tính ngay khi
bắt đầu, trừ VOIDED; lượt còn lại = `maxAttempts` + lượt được cấp − lượt đã dùng. `deadlineAt` =
min(bắt đầu + thời lượng, `closeAt`). Đề trả về không có đáp án đúng/giải thích. Mỗi câu có `code` là mã trong ngân hàng
(`questions.code`, lấy theo bản hiện tại của câu, null khi câu không có mã).

**Lưu đáp án.** PATCH chỉ nhận `selectedOptionIds` và/hoặc `flagged` (bỏ qua = giữ nguyên, `[]` = bỏ chọn, field
khác bị từ chối). ID phải thuộc đề đã phát; SINGLE tối đa 1. Chặn khi lượt không còn IN_PROGRESS hoặc quá
`deadlineAt + graceSeconds`. Lưu, nộp và job cùng khóa dòng lượt thi.

**Chấm.** Chỉ đọc snapshot. ALL_OR_NOTHING (và mọi câu SINGLE): đúng đủ mới có điểm. PARTIAL (câu MULTIPLE):
`points × max(0, đúng − sai) / số đáp án đúng`. Đạt khi `rawScore × 100 ≥ passPercent × maxScore` (so trên giá trị
chưa làm tròn); `scorePercent` cắt xuống 2 chữ số. Nộp sau hạn được ghi `AUTO_SUBMITTED` với giờ nộp = `deadlineAt`,
`submitType` = `TIMEOUT` hoặc `QUIZ_CLOSED` nếu bị cắt bởi giờ đóng đề.

**Tổng hợp.** `bestScore`, `lastScore` luôn có; `finalScore` và `passed` theo `scoreMethod`: BEST = điểm cao nhất /
từng đạt, LAST = lượt chấm gần nhất. `everPassed` dùng cho chặn thi lại và `reviewPolicy=AFTER_PASS`.

**Xem lại.** `review` có khi `AFTER_SUBMIT`; `AFTER_PASS` khi đã từng đạt; `AFTER_CLOSE` khi đề CLOSED hoặc quá
`closeAt`; `NEVER` không trả.

**Quản trị lượt thi.** Hủy lượt chuyển sang VOIDED với lý do, người và giờ hủy; lượt không tính vào số lượt đã dùng
và điểm nhưng bài làm được giữ. Hủy lượt đang làm giải phóng chỗ cho lượt mới; hủy lại lượt đã VOIDED trả
`ASSESSMENT-CONFLICT`. Cấp thêm lượt chỉ cho học viên có lần học của khóa; thu hồi giữ dòng lịch sử, lượt đã dùng
không bị hủy và số lượt còn lại không âm.

**Đóng, xóa, xem thử đề.** Đóng chỉ áp dụng cho đề PUBLISHED (đề DRAFT trả `ASSESSMENT-CONFLICT`, gọi lại khi đã
CLOSED trả cấu hình hiện tại). Khi đóng, mọi lượt đang làm được chấm ngay với đáp án đã lưu (`AUTO_SUBMITTED`,
`QUIZ_CLOSED`, giờ nộp không vượt deadline); mở lại bằng publish. Xóa chỉ cho đề chưa có lượt nào kể cả VOIDED
(`ASSESSMENT-QUIZ_IN_USE`). Xem thử dùng đúng quy tắc bốc đề của lượt thật nên mỗi lần gọi có thể khác nhau.

**Nộp hộ.** Chỉ lượt IN_PROGRESS; ghi `SUBMITTED`/`ADMIN`, giờ nộp không vượt deadline của lượt.

**Câu hỏi hàng loạt.** Cả lô thành công hoặc thất bại cùng nhau. Ngưng sử dụng bị chặn (`ASSESSMENT-QUIZ_NOT_READY`)
nếu làm đề đã xuất bản thiếu câu ACTIVE; xóa bị chặn (`ASSESSMENT-QUESTION_IN_USE`) khi câu còn trong pool đề chưa xóa.

**Nhập/xuất Excel câu hỏi.** Theo khuôn import tổ chức: .xlsx tối đa 5 MiB/20 MiB giải nén/100 entry ZIP, tối đa
1000 dòng, chỉ sheet `Instructions` và `Questions`, không công thức/ô gộp/cột lạ. Cột theo thứ tự:

| Cột | Quy tắc |
|---|---|
| ma | Văn bản ≤ 50; trống = sinh `Q<ID>`. Trùng mã trong tệp hoặc câu đã có là lỗi; `overwrite=true` ghi đè câu cùng mã trong cùng khóa (không chuyển khóa) |
| noi_dung | Bắt buộc |
| loai | `SINGLE`/`MULTIPLE` (chấp nhận `mot`/`nhieu`) |
| phuong_an | 2–10 phương án, phân tách `\|`; nội dung phương án không chứa `\|` |
| dap_an_dung | Chữ cái theo thứ tự phương án, phân tách `\|` (vd `A\|C`); SINGLE đúng 1, MULTIPLE ≥ 2 |
| giai_thich | Tùy chọn |
| do_kho | `EASY/MEDIUM/HARD` (chấp nhận Dễ/Trung bình/Khó); trống = MEDIUM |
| nhom_kien_thuc | **Mã** nhóm năng lực ACTIVE |
| diem | Số dương ≤ 9999.99, tối đa 2 chữ số thập phân; trống = 1 |
| trang_thai | `ACTIVE/INACTIVE`; trống = ACTIVE |

Mỗi dòng đi qua đúng validation của API tạo câu. Có lỗi: `ASSESSMENT-IMPORT_INVALID`, không ghi dòng nào,
`errors.field` = `Questions[dòng Excel].cột` (header là dòng 1, tối đa 200 lỗi); lỗi cấp tệp dùng field `file`.
dryRun không khóa/ghi; nhập thật kiểm tra lại dưới write lock và ghi audit từng câu cùng một bản tổng hợp
`ASSESSMENT_QUESTIONS_IMPORTED`. Ghi đè thay toàn bộ phương án (ID mới); câu đã phát đề tăng `contentVersion`.
Xuất trả tối đa 10.000 câu theo bộ lọc danh sách; tệp xuất nhập lại được ngay với `overwrite=true`.
Endpoint tải tệp (mẫu, xuất Excel, xuất CSV lượt thi) khai báo cả JSON để lỗi vẫn trả envelope chung.

**Phạm vi báo cáo.** SUPER_ADMIN/ADMIN/INSTRUCTOR/REPORT_VIEWER, xét role và scope trên cùng grant: GLOBAL = toàn hệ
thống, ORG = đơn vị và cây con active, TEAM = đúng nhóm; chỉ đọc. Không có grant phù hợp (vd chỉ LEARNER) trả 403.
Dữ liệu lọc theo đơn vị/nhóm **lúc thi** (`quiz_attempts.org_id/team_id`), riêng learners theo snapshot lúc ghi danh;
chuyển đơn vị không đổi báo cáo quá khứ. Bộ lọc orgId/teamId chỉ thu hẹp trong phạm vi. Giảng viên xem theo đơn vị
học viên, không theo khóa sở hữu. Đạt/điểm cuối mỗi học viên theo `scoreMethod` của từng đề; passRate =
học viên đạt / học viên có lượt chấm. Năng lực tính trên mọi lượt đã chấm. learnerStatus: `NOT_STARTED`,
`IN_PROGRESS`, `FAILED`, `PASSED`; attemptsLeft gồm lượt cấp thêm.

**Chấm lại.** Đáp án mới = phương án đúng hiện tại của `questionId` (phải thuộc khóa của đề), khớp theo ID phương án
trong snapshot từng lượt đã chấm (SUBMITTED/AUTO_SUBMITTED; lượt đang làm/VOIDED không đụng tới). Kết quả mỗi lượt:
`INCREASED` (chỉ loại này được áp dụng khi dryRun=false), `DECREASED` (chỉ báo, không hạ điểm học viên), `UNCHANGED`,
`SKIPPED_OPTIONS_CHANGED` (phương án đổi ID, vd ghi đè bằng Excel), `SKIPPED_TYPE_CHANGED`. Áp dụng: khóa từng lượt,
cập nhật snapshot đáp án, điểm câu và tổng điểm/đạt, ghi `attempt_regrades` (trước/sau, lý do, người chấm, batchId) và
một audit `ASSESSMENT_ATTEMPTS_REGRADED`; chạy lại không ghi trùng. Lịch sử hiển thị ở `regrades` của API 24.

**Bài quiz của khóa.** Khóa có thể có bài học loại quiz (`lessonType=quiz`, `quizId` trỏ tới đề của khóa; xem
`docs/api/LEARNING.md`). Khi một lượt được chấm `passed=true` thì `AttemptGrader` (nộp, hết giờ, nộp hộ) hoặc
`RegradeService` gọi `LearningAssessmentAccess.quizPassed`: bài quiz trỏ tới đề đó trong phiên bản học viên đã nhận lên
100% và tiến độ khóa được tính lại, nên khóa hoàn thành khi đây là bài bắt buộc cuối cùng. Chạy trong transaction chấm
bài; lần học đã rút hoặc không còn thì bỏ qua và điểm vẫn được giữ. Một chiều: tiến độ chỉ tăng nên chấm lại từ đạt
xuống trượt không hạ bài học (chấm lại vốn chỉ áp dụng khi điểm tăng). `requireLessonsCompleted` chỉ đếm bài `content`
để đề nằm trong khóa không tự chặn chính nó.

**Bài thi cuối khóa được chọn.** `DraftContent.finalQuizId` là ID một `FINAL_EXAM` cùng khóa, được lưu theo revision
đồng bộ trong `course_completion_rules.final_quiz_id` và dòng `course_assessments` có `placement=final`.
Khi đọc ưu tiên dòng liên kết, không có thì dùng cột điều kiện hoàn thành để giữ cấu hình cũ.
Xuất bản yêu cầu đề đã `PUBLISHED`. Bài này phải đạt ngay cả khi không
có trong cây bài học. Chỉ lượt `SUBMITTED`/`AUTO_SUBMITTED`, `passed=true`, đúng người và đúng `enrollmentId` mới được
tính; lượt của lần học cũ hoặc `VOIDED` không được dùng thay. Không cộng hai lần khi đề cũng là bài quiz bắt buộc
trong cây. Khóa chỉ hoàn thành sau khi mọi bài học bắt buộc và bài thi cuối đã đạt; bài quiz tùy chọn không chặn.
Nộp tay, hết giờ, nộp hộ và chấm lại đều kích hoạt cùng luồng. Không xóa được đề đang gắn vào nội dung hoặc điều kiện
hoàn thành (`ASSESSMENT-QUIZ_IN_USE`). Chạy `2026-11-05-required-final-quiz.sql` trước khi nâng backend.

**Job tự nộp.** Mỗi `app.assessment.auto-submit-interval` (mặc định 1 phút) quét tối đa 100 lượt quá
`deadlineAt + graceSeconds`, mỗi lượt một transaction có khóa dòng; chạy lại hoặc nhiều instance không chấm trùng.

<a id="reporting-read-model"></a>

## Đồng bộ dữ liệu tổng hợp cho Dashboard/báo cáo

Extension `docs/sql/learning_hub_reporting.sql` phải có trước khi chạy bản backend này. `AttemptGrader` cập nhật
read model sau nộp tay/tự nộp/nộp hộ; `RegradeService` cập nhật sau mỗi lượt áp dụng chấm lại; quản trị VOIDED
xóa điểm năng lực của lượt và tính lại kết quả theo đề/học viên. Mọi bước trong cùng transaction, lỗi rollback
cả kết quả gốc và read model. Không có trigger, job refresh toàn bảng hoặc cập nhật theo heartbeat video.

`AssessmentResultRepository` serialize theo `(quiz_id,user_id)` bằng advisory transaction lock riêng, không
khóa các lượt khác khi recompute. Kết quả tính lại từ lịch sử thay vì cộng số đếm; retry không cộng trùng. BEST
chọn score_percent cao nhất, hòa điểm chọn submitted_at DESC/attempt_id DESC; LAST theo thời điểm nộp, không
theo ngày chấm lại. `ever_passed` giữ semantics BEST hiện tại; không suy từ điểm làm tròn hoặc ngưỡng đề mới.
Nếu không còn lượt hợp lệ thì xóa dòng quiz_user_results, không tạo một kết quả 0 giả.

Báo cáo `/reports/assessment/competency` và `/me/competency` giữ cách chọn mọi lượt đã chấm trong filter, nhưng
đọc `attempt_competency_scores` trước khi tổng hợp. Radar Dashboard mới chọn một lượt mỗi đề (BEST/LAST), chỉ
thi cuối khóa; không đổi semantics API cũ. Các báo cáo theo kỳ/đơn vị vẫn lọc quiz_attempts trước aggregate.
Contract FE: [Trang chủ và báo cáo đào tạo](LEARNING.md#dashboard-reporting).

## Mã lỗi

| messageCode | Khi nào |
|---|---|
| ASSESSMENT-NOT_FOUND | Không tồn tại, đã xóa, đề DRAFT với học viên, hoặc lượt của người khác |
| ASSESSMENT-CONFLICT | `expectedVersion` lệch hoặc ghi đè đồng thời |
| ASSESSMENT-DUPLICATE | Trùng mã câu hỏi/nhóm năng lực, khóa đã có FINAL_EXAM |
| ASSESSMENT-INVALID_REFERENCE | Bài học/nhóm năng lực/câu hỏi trong pool không hợp lệ |
| ASSESSMENT-QUIZ_NOT_READY | Pool không đủ câu ACTIVE |
| ASSESSMENT-QUIZ_NOT_OPEN / QUIZ_CLOSED | Ngoài khoảng mở đề |
| ASSESSMENT-LESSONS_REQUIRED | Đề yêu cầu hoàn thành bài bắt buộc |
| ASSESSMENT-ALREADY_PASSED / NO_ATTEMPTS_LEFT / RETAKE_WAIT | Không được bắt đầu lượt mới |
| ASSESSMENT-ATTEMPT_CLOSED | Lưu đáp án khi lượt đã nộp/hết giờ |
| ASSESSMENT-ATTEMPT_IN_PROGRESS | Xem kết quả khi lượt chưa nộp |
| ASSESSMENT-QUESTION_IN_USE | Xóa câu hỏi còn nằm trong pool của đề |
| ASSESSMENT-QUIZ_IN_USE | Xóa đề đã có lượt thi |
| ASSESSMENT-COMPETENCY_IN_USE | Xóa nhóm năng lực còn câu hỏi hoặc nhóm con |
| ASSESSMENT-EXPORT_TOO_LARGE | Xuất CSV/Excel vượt 10.000 dòng |
| ASSESSMENT-IMPORT_INVALID | Tệp nhập câu hỏi có lỗi tệp hoặc lỗi dòng |

Thiếu lần học hợp lệ trả mã Learning `LEARNING-NOT_AVAILABLE`; thiếu quyền quản lý khóa trả HTTP 403.

## Kiểm thử

Integration test tại `src/test/java/vn/com/coreacademy/assessment` (8 lớp, thêm `QuizLessonContentIT`, `QuizLessonCompletionIT`, `LearnerCourseQuizzesIT`) gọi API qua MockMvc với JWT của
tài khoản seed, dựng khóa học bằng chính API Learning (danh mục → khóa → xuất bản → giao học) rồi kiểm tra envelope
BaseResponse, field JSON FE dùng, mã lỗi/field path, phân quyền 401/403, cách ly dữ liệu giữa học viên, không lộ đáp án
khi đang thi, hết giờ/job tự nộp, đóng đề, chấm PARTIAL, BEST/LAST, snapshot khi sửa câu, bắt đầu đồng thời và đủ 47
operation trên `/v3/api-docs`, nhập/xuất Excel, báo cáo (số liệu tính tay, phạm vi GLOBAL/ORG/TEAM) và chấm lại (chỉ áp dụng tăng điểm).

- Có Docker: `mvn clean verify` (Testcontainers pgvector, giống test Knowledge).
- Không có Docker: chỉ định PostgreSQL 14+ được phép tạo/xóa database tạm
  `mvn clean verify -Dit.postgres.url=jdbc:postgresql://localhost:5432/postgres [-Dit.postgres.user=… -Dit.postgres.password=…]`.
  Test tạo lại database `coreacademy_assessment_it` và chạy 5 file SQL; không dùng database ứng dụng.
- Không có cả hai: các test này bị bỏ qua (skipped), không fail.

Mọi mốc thời gian của lượt thi được cắt về micro giây trước khi lưu để response ngay sau khi ghi khớp giá trị đọc lại.

## Chưa làm / cần chốt

- Liên kết `course_assessments` chỉ dùng `placement=final` qua `finalQuizId` của revision; chưa có API cho các vị trí
  khác. Bài quiz trong cây nội dung tiếp tục dùng `course_lessons.quiz_id`.
- Bài quiz không tự hạ về chưa hoàn thành khi chấm lại làm điểm giảm hoặc khi đề bị đóng sau đó (tiến độ chỉ tăng).
- Chưa có `quiz_revision`: lượt mới của học viên khóa cũ dùng cấu hình đề hiện tại.
- Chấm lại chưa áp dụng cho lượt đang làm (lượt đó chấm theo snapshot khi nộp; chạy lại chấm lại sau khi nộp).

## Flashcard ôn tập

| API | Mục đích |
|---|---|
| `GET /api/v1/courses/{courseId}/flashcards` | Xem trạng thái duyệt của thẻ trong khóa thuộc phạm vi quản lý |
| `PUT /api/v1/courses/{courseId}/flashcards/{questionId}` | Lưu nháp, công bố hoặc thu hồi bằng `status` tương ứng `DRAFT`, `PUBLISHED`, `WITHDRAWN`. Khi công bố, gửi `questionVersion` bằng `rowVersion` của câu hỏi đã duyệt |
| `GET /api/v1/me/courses/{courseId}/flashcards` | Lấy mặt trước và trạng thái cá nhân của các thẻ trong khóa học viên có quyền học |
| `GET /api/v1/me/courses/{courseId}/flashcards/{cardId}/answer?questionVersion=...` | Lấy đáp án đúng và giải thích khi lật thẻ |
| `PATCH /api/v1/me/courses/{courseId}/flashcards/{cardId}/progress` | Gửi `questionVersion` và ít nhất một trong hai trường `rating` (`AGAIN`, `KNOWN`) hoặc `starred` |

Câu hỏi đã sửa phải được duyệt lại trước khi học viên thấy phiên bản mới. Câu hỏi đã từng công bố đáp án qua flashcard, kể cả đã thu hồi, không được đưa vào đề thi cuối khóa. Câu hỏi đã thuộc đề thi cuối khóa không được công bố qua flashcard.

### Bộ flashcard cá nhân

Chạy `upgrade/2026-11-07-personal-flashcards.sql` sau script flashcard trước khi triển khai. Các API `/api/v1/me/flashcard-decks` hỗ trợ GET/POST bộ thẻ, PUT/DELETE bộ theo ID, GET/POST thẻ trong bộ, PUT/DELETE thẻ và PATCH trạng thái ôn hoặc đánh dấu. Chủ sở hữu được xác định từ phiên đăng nhập. Các thao tác sửa nội dung đặt lại trạng thái ôn của thẻ. Khóa học và nhóm kiến thức có thể được truyền qua API khi tạo hoặc sửa.

API `POST /api/v1/me/flashcard-decks/{deckId}/cards/batch` nhận `items` gồm 1–100 thẻ với `prompt`, `answer`, `competencyId` tùy chọn. Tất cả thẻ được lưu cùng giao dịch.
