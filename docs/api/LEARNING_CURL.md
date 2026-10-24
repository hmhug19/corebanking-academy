# cURL kiểm tra API Learning

Các ví dụ dùng Bash/Git Bash/WSL và cURL. Trên PowerShell có thể import collection Postman đi kèm để tránh khác biệt escape JSON. Đây là request mẫu, chưa được chạy với tài khoản/DB của bạn.

## Chuẩn bị

1. Review và áp dụng `docs/sql/learning_hub_learning.sql` một lần sau Identity trên môi trường được phép. Không tự chạy startup; không chạy lại file trên schema đã có các bảng Learning.
2. Build/chạy ứng dụng với cấu hình hiện có, mặc định cổng 8019. Cấu hình S3_ENDPOINT/S3_BUCKET/S3_REGION/S3_PATH_STYLE_ACCESS/S3_ACCESS_KEY/S3_SECRET_KEY; local dùng endpoint AIStor Ubuntu truy cập được từ máy BE, xem DEPLOY_UBUNTU.md. Không còn kho file local.
3. Dùng tài khoản giảng viên có scope phù hợp (ORG/GLOBAL cho luồng nhóm/đơn vị) và học viên LEARNER/OWN đang active, không cần đổi mật khẩu. Lấy token qua API login hiện có bằng thông tin đã được phép sử dụng; không cấp quyền/reset password để kiểm tra.
4. Đặt các biến dưới đây bằng ID/token thật được phép. Không giả định org_id hoặc user_id theo ví dụ. Mỗi response nghiệp vụ cần kiểm tra `status`, không chỉ HTTP 200.

```bash
BASE_URL='http://localhost:8019'
INSTRUCTOR_TOKEN='TOKEN_GIANG_VIEN'
LEARNER_TOKEN='TOKEN_HOC_VIEN'
ORG_ID=123                    # thay bằng tổ chức được phép quản lý
LEARNER_ID='MA_CAN_BO'        # chuỗi, giữ số 0 đầu
TARGET_ORG_ID=456             # chỉ dùng khi kiểm tra chia sẻ
CATALOG_VERSION=0
REVISION_VERSION=0
COURSE_VERSION=0
GROUP_VERSION=0
PROGRESS_VERSION=0
PROGRESS_SEQUENCE=1
SESSION_ID='UUID_PHIEN_DO_BE_CAP'
SESSION_FENCE=1
PROGRESS_ASSET_ID=null       # ID asset với PDF/video; null với khối không có media chính
# FIELD_ID, PROGRAM_ID, MODULE_ID, COURSE_ID, REVISION_ID, GROUP_ID,
# ASSIGNMENT_ID, RECIPIENT_ID, ENROLLMENT_ID, BLOCK_ID, ASSET_ID, REMINDER_ID:
# lấy từ response của request trước, tuyệt đối không giả định luôn bằng 1.
```

Sau tạo danh mục: lấy `data.id`. Sau tạo/lưu khóa: lấy `data.course.id`, `data.revision.id`, `data.revision.rowVersion`; sau lưu lấy BLOCK_ID từ `data.chapters[0].lessons[0].blocks[0].block.id`. Sau giao: lấy recipient đúng LEARNER_ID trong `data.recipients`, giữ `id` và `enrollmentId`. Version cần cập nhật từ response tương ứng.

## Luồng tối thiểu nên chạy trước

Tạo field → program → module → course → PUT draft → publish → assignment → GET khóa của tôi → GET enrollment → POST sessions → PUT progress cho từng khối bắt buộc → GET enrollment kiểm tra kết quả.

Ví dụ PUT nội dung có một bài với hai khối bắt buộc (text và process), nên phải cập nhật tiến độ cả hai khối mới hoàn thành bài/khóa. Khi chuyển sang block chưa có tiến độ, đặt lại PROGRESS_VERSION=0 và PROGRESS_SEQUENCE=1 cho block đó. Khi tạo group rồi thay members, có thể đổi target assignment thành `learning_group` với groupId và userId=null. Muốn kiểm tra giao trùng, thêm target cá nhân cùng người vào cùng đợt; dùng idempotencyKey mới nếu đổi payload.

Các thao tác copy/draft/pause/share/revoke là kiểm tra riêng sau luồng chính. Không chạy lần lượt mọi cURL mà bỏ qua trạng thái/ID trả về. Sau mở draft mới, muốn giao bản cũ phải giữ revisionId đã publish riêng. `CATALOG_VERSION` là rowVersion của đúng danh mục đang sửa.

Postman collection tự giữ nhiều ID/version từ response; vẫn cần nhập token, orgId, learnerId, chọn request theo luồng và chọn file cho multipart. Mẫu deadline 2030 chỉ để dễ chạy thử, phải sửa thành hạn nghiệp vụ thực tế.

## Danh sách các request

| STT | Method / đường dẫn | Mục đích |
|---|---|---|
| 1 | GET `/api/v1/training-catalog/fields` | Danh sách fields |
| 2 | POST `/api/v1/training-catalog/fields` | Tạo fields |
| 3 | PUT `/api/v1/training-catalog/fields/{fieldId}` | Cập nhật fields |
| 4 | GET `/api/v1/training-catalog/programs` | Danh sách programs |
| 5 | POST `/api/v1/training-catalog/programs` | Tạo programs |
| 6 | PUT `/api/v1/training-catalog/programs/{programId}` | Cập nhật programs |
| 7 | GET `/api/v1/training-catalog/modules` | Danh sách modules |
| 8 | POST `/api/v1/training-catalog/modules` | Tạo modules |
| 9 | PUT `/api/v1/training-catalog/modules/{moduleId}` | Cập nhật modules |
| 10 | POST `/api/v1/courses` | Tạo khóa học và bản nháp đầu tiên |
| 11 | GET `/api/v1/courses/{courseId}` | Xem cây nội dung để biên soạn |
| 12 | PUT `/api/v1/courses/{courseId}/revisions/{revisionId}` | Lưu toàn bộ nội dung bản nháp |
| 13 | POST `/api/v1/courses/{courseId}/revisions/{revisionId}/publish` | Xuất bản khóa học |
| 14 | POST `/api/v1/courses/{courseId}/draft` | Mở bản nháp để chỉnh sửa |
| 15 | POST `/api/v1/courses/{courseId}/copy` | Sao chép thành khóa mới |
| 16 | PATCH `/api/v1/courses/{courseId}/state` | Tạm dừng hoặc lưu trữ khóa học |
| 17 | GET `/api/v1/courses/{courseId}/shares` | Xem chia sẻ khóa học |
| 18 | PUT `/api/v1/courses/{courseId}/shares` | Chia sẻ hoặc thu hồi chia sẻ |
| 19 | POST `/api/v1/courses/{courseId}/assignments` | Giao khóa học cho cá nhân/nhóm/đơn vị |
| 20 | GET `/api/v1/courses/{courseId}/assignments` | Xem các đợt giao của đơn vị |
| 21 | POST `/api/v1/courses/{courseId}/enrollments` | Tự đăng ký khóa học tự chọn |
| 22 | GET `/api/v1/learning-groups` | Danh sách nhóm học tập |
| 23 | POST `/api/v1/learning-groups` | Tạo nhóm học tập |
| 24 | GET `/api/v1/learning-groups/{groupId}` | Xem nhóm và thành viên |
| 25 | PUT `/api/v1/learning-groups/{groupId}/members` | Thay danh sách thành viên nhóm học |
| 26 | GET `/api/v1/instructor/courses` | Danh sách khóa học giảng viên |
| 27 | GET `/api/v1/learning-assignments/{assignmentId}` | Xem đợt giao và người nhận |
| 28 | POST `/api/v1/learning-assignments/recipients/{recipientId}/revoke` | Thu hồi một nghĩa vụ giao học |
| 29 | GET `/api/v1/me/learning/courses` | Khóa học của tôi |
| 30 | GET `/api/v1/me/learning/catalog` | Danh mục khóa học của học viên |
| 31 | GET `/api/v1/me/learning/enrollments/{enrollmentId}` | Xem nội dung và vị trí tiếp tục học |
| 32 | PUT `/api/v1/me/learning/enrollments/{enrollmentId}/blocks/{blockId}/progress` | Ghi tương tác học và tổng hợp tiến độ |
| 33 | GET `/api/v1/me/learning/reminders` | Thông báo nhắc học đến hạn |
| 34 | POST `/api/v1/me/learning/reminders/{reminderId}/read` | Đánh dấu đã đọc nhắc học |
| 35 | POST `/api/v1/learning-assets` | Upload tài nguyên |
| 36 | GET `/api/v1/learning-assets/{assetId}/content` | Tải tài nguyên |

## 1. Danh sách fields

Danh mục được lọc quyền và phân trang tại database.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/training-catalog/fields?ownerOrgId=${ORG_ID}&page=0&size=20" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 2. Tạo fields

Mã được kiểm tra trùng trong transaction; các ID cha phải tồn tại và thuộc đúng tổ chức.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/training-catalog/fields" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "ownerOrgId": ${ORG_ID},
  "fieldId": null,
  "code": "FIELD-DEMO-01",
  "name": "Nghiệp vụ Core Banking",
  "description": "Nội dung thử nghiệm do người kiểm thử tạo.",
  "status": "published",
  "sortNo": 0
}
JSON
```

## 3. Cập nhật fields

Thay thông tin danh mục, không chuyển tổ chức/lĩnh vực; paused chặn học theo danh mục.

```bash
curl --silent --show-error --request PUT "${BASE_URL}/api/v1/training-catalog/fields/${FIELD_ID}?expectedVersion=${CATALOG_VERSION}" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "ownerOrgId": ${ORG_ID},
  "fieldId": null,
  "code": "FIELD-DEMO-01",
  "name": "Nghiệp vụ Core Banking",
  "description": "Nội dung thử nghiệm do người kiểm thử tạo.",
  "status": "published",
  "sortNo": 0
}
JSON
```

## 4. Danh sách programs

Danh mục được lọc quyền và phân trang tại database.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/training-catalog/programs?ownerOrgId=${ORG_ID}&page=0&size=20" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 5. Tạo programs

Mã được kiểm tra trùng trong transaction; các ID cha phải tồn tại và thuộc đúng tổ chức.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/training-catalog/programs" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "ownerOrgId": ${ORG_ID},
  "fieldId": ${FIELD_ID},
  "code": "PROGRAM-DEMO-01",
  "name": "Nền tảng nghiệp vụ",
  "description": "Nội dung thử nghiệm do người kiểm thử tạo.",
  "status": "published",
  "sortNo": 0
}
JSON
```

## 6. Cập nhật programs

Thay thông tin danh mục, không chuyển tổ chức/lĩnh vực; paused chặn học theo danh mục.

```bash
curl --silent --show-error --request PUT "${BASE_URL}/api/v1/training-catalog/programs/${PROGRAM_ID}?expectedVersion=${CATALOG_VERSION}" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "ownerOrgId": ${ORG_ID},
  "fieldId": ${FIELD_ID},
  "code": "PROGRAM-DEMO-01",
  "name": "Nền tảng nghiệp vụ",
  "description": "Nội dung thử nghiệm do người kiểm thử tạo.",
  "status": "published",
  "sortNo": 0
}
JSON
```

## 7. Danh sách modules

Danh mục được lọc quyền và phân trang tại database.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/training-catalog/modules?fieldId=${FIELD_ID}&page=0&size=20" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 8. Tạo modules

Mã được kiểm tra trùng trong transaction; các ID cha phải tồn tại và thuộc đúng tổ chức.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/training-catalog/modules" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "ownerOrgId": ${ORG_ID},
  "fieldId": ${FIELD_ID},
  "code": "MODULE-DEMO-01",
  "name": "Tổng quan",
  "description": "Nội dung thử nghiệm do người kiểm thử tạo.",
  "status": "published",
  "sortNo": 0
}
JSON
```

## 9. Cập nhật modules

Thay thông tin danh mục, không chuyển tổ chức/lĩnh vực; paused chặn học theo danh mục.

```bash
curl --silent --show-error --request PUT "${BASE_URL}/api/v1/training-catalog/modules/${MODULE_ID}?expectedVersion=${CATALOG_VERSION}" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "ownerOrgId": ${ORG_ID},
  "fieldId": ${FIELD_ID},
  "code": "MODULE-DEMO-01",
  "name": "Tổng quan",
  "description": "Nội dung thử nghiệm do người kiểm thử tạo.",
  "status": "published",
  "sortNo": 0
}
JSON
```

## 10. Tạo khóa học và bản nháp đầu tiên

Tạo khóa thuộc phạm vi giảng viên, mã khóa duy nhất và chủ sở hữu do backend xác định.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/courses" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "ownerOrgId": ${ORG_ID},
  "ownerTeamId": null,
  "code": "COURSE-DEMO-01",
  "title": "Tổng quan Core Banking"
}
JSON
```

## 11. Xem cây nội dung để biên soạn

Chỉ người có quyền sửa khóa được xem bản nháp; không gửi revisionId sẽ lấy bản mới nhất.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/courses/${COURSE_ID}" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 12. Lưu toàn bộ nội dung bản nháp

PUT thay toàn bộ cây nháp và cấp lại ID chương/bài/khối; thứ tự theo mảng. Gửi expectedVersion mới nhất.

```bash
curl --silent --show-error --request PUT "${BASE_URL}/api/v1/courses/${COURSE_ID}/revisions/${REVISION_ID}" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "expectedVersion": ${REVISION_VERSION},
  "programId": ${PROGRAM_ID},
  "moduleId": ${MODULE_ID},
  "title": "Tổng quan Core Banking",
  "summary": "Khóa đào tạo nội bộ.",
  "level": "basic",
  "defaultRequirement": "optional",
  "coverAssetId": null,
  "coverIcon": "layers",
  "coverColor": "#E3F1EF",
  "objectives": [
    "Hiểu cấu trúc và các bước vận hành."
  ],
  "chapters": [
    {
      "title": "Chương 1: Tổng quan",
      "lessons": [
        {
          "title": "Bài 1: Khái niệm",
          "lessonType": "content",
          "estimatedDurationSeconds": 300,
          "isRequired": true,
          "blocks": [
            {
              "blockType": "text",
              "title": "Giới thiệu",
              "body": "## Nội dung\nTổng quan nghiệp vụ Core Banking.",
              "bodyFormat": "markdown",
              "completionMode": "manual",
              "completionThreshold": null,
              "isRequired": true,
              "assets": [],
              "steps": []
            },
            {
              "blockType": "process",
              "title": "Quy trình cơ bản",
              "body": null,
              "bodyFormat": null,
              "completionMode": "manual",
              "completionThreshold": null,
              "isRequired": true,
              "assets": [],
              "steps": [
                {
                  "title": "Tiếp nhận yêu cầu",
                  "description": "Kiểm tra thông tin đầu vào."
                },
                {
                  "title": "Xác nhận",
                  "description": "Lưu kết quả xử lý."
                }
              ]
            }
          ]
        }
      ]
    }
  ],
  "rewardPoints": 0
}
JSON
```

## 13. Xuất bản khóa học

Kiểm tra nội dung, danh mục và tài nguyên trước khi đóng băng revision. Không reset tiến độ cũ.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/courses/${COURSE_ID}/revisions/${REVISION_ID}/publish" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "expectedVersion": ${REVISION_VERSION}
}
JSON
```

## 14. Mở bản nháp để chỉnh sửa

Tái sử dụng draft hiện có hoặc clone từ bản published; không sao chép tiến độ.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/courses/${COURSE_ID}/draft" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 15. Sao chép thành khóa mới

Cấp mã mới và clone nội dung thành draft; không copy người học, giao học hoặc chia sẻ.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/courses/${COURSE_ID}/copy" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "code": "COURSE-DEMO-COPY-01"
}
JSON
```

## 16. Tạm dừng hoặc lưu trữ khóa học

Nhận active/paused/archived và expectedVersion của courses; giữ lịch sử học.

```bash
curl --silent --show-error --request PATCH "${BASE_URL}/api/v1/courses/${COURSE_ID}/state" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "expectedVersion": ${COURSE_VERSION},
  "status": "paused"
}
JSON
```

## 17. Xem chia sẻ khóa học

Chỉ người quản lý nội dung khóa được xem và sửa quyền khai thác theo đơn vị.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/courses/${COURSE_ID}/shares" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 18. Chia sẻ hoặc thu hồi chia sẻ

Chia sẻ cấp quyền khai thác, không cấp quyền sửa nội dung. Thu hồi không xóa giao học cũ.

```bash
curl --silent --show-error --request PUT "${BASE_URL}/api/v1/courses/${COURSE_ID}/shares" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "orgId": ${TARGET_ORG_ID},
  "includeDescendants": false,
  "canAssign": true,
  "canSelfEnroll": true,
  "active": true
}
JSON
```

## 19. Giao khóa học cho cá nhân/nhóm/đơn vị

Chốt người nhận khi giao, giới hạn 500 học viên/đợt và giữ idempotencyKey khi retry cùng payload.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/courses/${COURSE_ID}/assignments" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "revisionId": ${REVISION_ID},
  "assigningOrgId": ${ORG_ID},
  "title": "Đợt đào tạo đầu tiên",
  "startAt": "2020-01-01T00:00:00Z",
  "dueAt": "2030-12-31T16:59:59Z",
  "requirement": "mandatory",
  "enrollmentMode": "continue",
  "reminderEnabled": true,
  "reminderDaysBefore": 3,
  "idempotencyKey": "learning-demo-assignment-01",
  "targets": [
    {
      "targetType": "user",
      "userId": "${LEARNER_ID}",
      "teamId": null,
      "orgId": null,
      "groupId": null,
      "includeDescendants": false
    }
  ]
}
JSON
```

## 20. Xem các đợt giao của đơn vị

Kiểm tra phạm vi assigningOrgId và quyền khai thác khóa trước phân trang.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/courses/${COURSE_ID}/assignments?assigningOrgId=${ORG_ID}&page=0&size=20" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 21. Tự đăng ký khóa học tự chọn

Học viên tự đăng ký bản published được phép khám phá; giữ lần học tương thích nếu đã có.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/courses/${COURSE_ID}/enrollments" \
  --header "Authorization: Bearer ${LEARNER_TOKEN}"
```

## 22. Danh sách nhóm học tập

Chỉ trả nhóm của tổ chức trong phạm vi quản lý.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/learning-groups?ownerOrgId=${ORG_ID}&page=0&size=20" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 23. Tạo nhóm học tập

Nhóm học độc lập nhóm chuyên môn, không tạo hay cấp role Identity.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/learning-groups" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "ownerOrgId": ${ORG_ID},
  "code": "GROUP-DEMO-01",
  "name": "Nhóm đào tạo mới",
  "description": "Nhóm học tự lập."
}
JSON
```

## 24. Xem nhóm và thành viên

Trả các membership đang hiệu lực; lịch sử rời nhóm vẫn được giữ.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/learning-groups/${GROUP_ID}" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 25. Thay danh sách thành viên nhóm học

Thêm/rời nhóm trong một transaction; không thay snapshot người nhận của các đợt giao trước.

```bash
curl --silent --show-error --request PUT "${BASE_URL}/api/v1/learning-groups/${GROUP_ID}/members" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "expectedVersion": ${GROUP_VERSION},
  "userIds": [
    "${LEARNER_ID}"
  ]
}
JSON
```

## 26. Danh sách khóa học giảng viên

Lọc owner/scope trước count và phân trang; số người được giao là số user khác nhau.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/instructor/courses?page=0&size=20" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 27. Xem đợt giao và người nhận

Chỉ quản lý đơn vị giao được xem danh sách người nhận và các nguồn target.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/learning-assignments/${ASSIGNMENT_ID}" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

## 28. Thu hồi một nghĩa vụ giao học

Không xóa enrollment/điểm/tiến độ; giữ quyền học nếu còn nguồn hợp lệ khác.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/learning-assignments/recipients/${RECIPIENT_ID}/revoke" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "reason": "Thu hồi giao học theo yêu cầu người kiểm thử."
}
JSON
```

## 29. Khóa học của tôi

Một thẻ mỗi khóa ở lần học hiện hành; tính nghĩa vụ và hạn từ giao học thực tế.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/me/learning/courses?page=0&size=20" \
  --header "Authorization: Bearer ${LEARNER_TOKEN}"
```

## 30. Danh mục khóa học của học viên

Bao gồm khóa được giao và khóa được phép khám phá chưa đăng ký.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/me/learning/catalog?page=0&size=20" \
  --header "Authorization: Bearer ${LEARNER_TOKEN}"
```

## 31. Xem nội dung và vị trí tiếp tục học

Chỉ hồ sơ học của caller; trả cây revision đã nhận và tiến độ từng khối.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/me/learning/enrollments/${ENROLLMENT_ID}" \
  --header "Authorization: Bearer ${LEARNER_TOKEN}"
```

## 32. Ghi tương tác học và tổng hợp tiến độ

Gửi expectedVersion/sequence; backend tự tính tỷ lệ, kiểm tra trang PDF và khoảng xem video.

```bash
curl --silent --show-error --request PUT "${BASE_URL}/api/v1/me/learning/enrollments/${ENROLLMENT_ID}/blocks/${BLOCK_ID}/progress" \
  --header "Authorization: Bearer ${LEARNER_TOKEN}" \
  --header "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "sessionId": "${SESSION_ID}",
  "sessionFence": ${SESSION_FENCE},
  "assetId": ${PROGRESS_ASSET_ID},
  "eventType": "COMPLETE",
  "playing": false,
  "expectedVersion": ${PROGRESS_VERSION},
  "sequence": ${PROGRESS_SEQUENCE},
  "complete": true,
  "videoPositionSeconds": null,
  "watchedFromSeconds": null,
  "watchedToSeconds": null,
  "pdfPage": null
}
JSON
```

## 33. Thông báo nhắc học đến hạn

Thông báo trong ứng dụng, chỉ người nhận đang cần học được thấy.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/me/learning/reminders" \
  --header "Authorization: Bearer ${LEARNER_TOKEN}"
```

## 34. Đánh dấu đã đọc nhắc học

Chỉ caller sở hữu người nhận được đánh dấu thông báo đã đọc.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/me/learning/reminders/${REMINDER_ID}/read" \
  --header "Authorization: Bearer ${LEARNER_TOKEN}"
```

## 35. Upload tài nguyên

BE xác định thời lượng/số trang từ file; không cần gửi durationSeconds/pageCount.

```bash
curl --silent --show-error --request POST "${BASE_URL}/api/v1/learning-assets?ownerOrgId=${ORG_ID}" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --form "file=@/duong-dan/tai-lieu.pdf"
```

## 36. Tải tài nguyên

Học viên claim session trước để có cookie media, gửi blockId và enrollmentId; giảng viên preview dùng Bearer.

```bash
curl --silent --show-error --request GET "${BASE_URL}/api/v1/learning-assets/${ASSET_ID}/content?blockId=${BLOCK_ID}&enrollmentId=${ENROLLMENT_ID}" \
  --cookie media-cookie.txt \
  --output tai-lieu-tai-ve.pdf
```

## Kiểm tra lỗi quan trọng

- Bỏ header Authorization: HTTP 401 với envelope lỗi.
- Dùng token học viên gọi lưu/xuất bản/giao: HTTP 403, không thay dữ liệu.
- Gửi expectedVersion cũ: HTTP 200, status=ERROR, messageCode=LEARNING-CONFLICT.
- Tái dùng idempotencyKey với payload khác: LEARNING-CONFLICT; cùng payload không tạo đợt giao mới.
- Gửi blockId của revision khác vào enrollment: LEARNING-INVALID_REFERENCE.
- Publish khi bài trống: LEARNING-NOT_PUBLISHABLE.
- Gửi lessonType=quiz: LEARNING-QUIZ_UNAVAILABLE; module thi chưa triển khai, không chấm điểm giả.
- Upload sai signature/extension/MIME/dung lượng: LEARNING-ASSET_INVALID (giới hạn multipart toàn request có thể trả 413).

## Video và PDF

Upload MP4/WebM hoặc PDF; BE xác minh codec/thời lượng bằng ffprobe và đếm trang bằng PDFBox. MP4 được remux faststart bằng ffmpeg. Không tin metadata do client gửi; chờ status=ready trước khi gắn file vào bài.

Để dùng file trong draft, thêm asset vào `blocks[].assets`: `{"assetId":ASSET_ID,"usage":"primary","caption":null,"altText":null}`. Video dùng blockType=video, completionMode=video_coverage, completionThreshold=90; PDF dùng blockType=pdf và manual/pdf_end. Mỗi video/PDF là một khối riêng. Sau PUT phải lấy lại blockId vì cây draft được thay thế.

Heartbeat video đầu tiên gửi vị trí nhưng không gửi khoảng đã xem. Sau đó chờ đúng thời gian xem, tăng sequence và dùng data.block.rowVersion mới nhất và receipt hạn phiên trong response. Sequence bắt đầu lại từ 1 khi claim/handoff phiên mới. Chỉ gửi khoảng từ vị trí heartbeat trước tới vị trí hiện tại; backend giới hạn thời lượng theo đồng hồ server, tốc độ tối đa 2x. Tua đến cuối chỉ thay vị trí tiếp tục, không tăng coverage.

```json
{"sessionId":"<SESSION_ID>","sessionFence":1,"assetId":501,"expectedVersion":0,"sequence":1,"eventType":"PLAY","playing":true,"complete":false,"videoPositionSeconds":0}
```

Sau khoảng 10 giây, gửi (version lấy từ response, không mặc định luôn 0):

```json
{"sessionId":"<SESSION_ID>","sessionFence":1,"assetId":501,"expectedVersion":1,"sequence":2,"eventType":"HEARTBEAT","playing":true,"complete":false,"videoPositionSeconds":10,"watchedFromSeconds":0,"watchedToSeconds":10}
```

PDF gửi pdfPage trong 1..pageCount; không gửi thông tin video. Text/process/manual gửi complete=true. Backend không nhận progressPercent, passed hoặc score do client tự đặt.

## Media/tiến độ bổ sung (37–46)

Contract chi tiết và giới hạn: [LEARNING.md](LEARNING.md#my-learning-progress-design).
Collection Postman đã thêm 10 request mới, request 32 bắt buộc claim session qua request 44 trước.
Khi đổi block, handoff phiên với previousSessionId/previousFence rồi lấy version/sequence/assetId mới.

Khởi tạo upload: đặt UPLOAD_REQUEST_ID UUID, FILE_NAME/MIME/SIZE và FILE_SHA256 đúng file trước khi chạy:

```bash
curl --request POST "${BASE_URL}/api/v1/learning-assets/uploads" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header 'Content-Type: application/json' \
  --data "{\"requestId\":\"${UPLOAD_REQUEST_ID}\",\"ownerOrgId\":${ORG_ID},\"originalName\":\"${FILE_NAME}\",\"mimeType\":\"${FILE_MIME}\",\"sizeBytes\":${FILE_SIZE},\"checksumSha256\":\"${FILE_SHA256}\"}"
```

Gửi từng part, GET manifest để resume rồi complete; UPLOAD_ID lấy từ response init, PART_SHA256 theo đúng part:

```bash
curl --request PUT "${BASE_URL}/api/v1/learning-assets/uploads/${UPLOAD_ID}/parts/1" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}" \
  --header 'Content-Type: application/octet-stream' \
  --header "X-Part-SHA256: ${PART_SHA256}" --data-binary @file.part001
curl --request POST "${BASE_URL}/api/v1/learning-assets/uploads/${UPLOAD_ID}/complete" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
curl "${BASE_URL}/api/v1/learning-assets/uploads/${UPLOAD_ID}" \
  --header "Authorization: Bearer ${INSTRUCTOR_TOKEN}"
```

Mở session lưu cookie để thử player; không đặt Bearer token vào URL:

```bash
curl --cookie-jar media-cookie.txt --request POST "${BASE_URL}/api/v1/me/learning/sessions" \
  --header "Authorization: Bearer ${LEARNER_TOKEN}" --header 'Content-Type: application/json' \
  --data "{\"enrollmentId\":${ENROLLMENT_ID},\"blockId\":${BLOCK_ID},\"takeover\":false}"
curl --cookie media-cookie.txt --header 'Range: bytes=0-99' \
  "${BASE_URL}/api/v1/learning-assets/${ASSET_ID}/content?enrollmentId=${ENROLLMENT_ID}&blockId=${BLOCK_ID}"
curl "${BASE_URL}/api/v1/me/learning/progress/summary" --header "Authorization: Bearer ${LEARNER_TOKEN}"
curl --output learning-progress.csv "${BASE_URL}/api/v1/me/learning/progress/export?sort=id,asc" \
  --header "Authorization: Bearer ${LEARNER_TOKEN}"
```

Cookie jar chứa token ngắn hạn, giữ local và không commit. File video cần ffprobe/ffmpeg trên máy BE;
Docker runtime đã cài công cụ này. Mọi request phải kiểm status/messageCode hoặc HTTP/header khi trả binary/CSV.
