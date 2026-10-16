# Auth API

Base path: `/api/v1/auth`. Login nhận username hoặc mã cán bộ; `/me` và `/logout` yêu cầu `Authorization: Bearer <accessToken>`.

## POST /login

Body LoginRequest trực tiếp: `{"username":"hunghm8","password":"<PASSWORD>"}`. HTTP 200 trả BaseResponse<TokenResponse>; JWT tại `data.accessToken`. Chi tiết envelope và lỗi: [API_CONTRACT.md](API_CONTRACT.md).

## GET /me

HTTP 200; Cache-Control: no-store. Payload sau nằm trong `BaseResponse.data`, không còn ở root response. Ví dụ minh họa, các trường snapshot nguồn BRN/BRCD/CRA/BDS/dept/QLNS và lastSyncTime không liệt kê hết trong ví dụ:

```json
{
  "user": {
    "userId": "183617",
    "username": "hunghm8",
    "fullName": "Nguyễn Văn A",
    "email": "user@example.com",
    "status": "active",
    "authProvider": "LOCAL",
    "orgId": 1,
    "orgCode": "IT",
    "orgName": "Phòng công nghệ",
    "orgType": "DEPARTMENT",
    "teamId": 10,
    "teamCode": "CORE",
    "teamName": "Nhóm Core",
    "positionName": "Chuyên viên"
  },
  "roles": [
    "INSTRUCTOR",
    "LEARNER"
  ],
  "functions": [
    "COURSE_LEARNING",
    "COURSE_MANAGEMENT"
  ],
  "roleGrants": [
    {
      "roleCode": "LEARNER",
      "roleName": "Học viên",
      "scope": {
        "type": "OWN",
        "orgId": null,
        "teamId": null
      }
    },
    {
      "roleCode": "INSTRUCTOR",
      "roleName": "Giảng viên",
      "scope": {
        "type": "ORG",
        "orgId": 1,
        "teamId": null
      }
    }
  ],
  "functionDetails": [
    {
      "id": 1,
      "code": "COURSE_LEARNING",
      "name": "Học tập",
      "routePattern": "/learn",
      "parentFunctionId": null,
      "sortNo": 20
    },
    {
      "id": 2,
      "code": "COURSE_MANAGEMENT",
      "name": "Quản lý khóa học",
      "routePattern": "/admin/courses",
      "parentFunctionId": null,
      "sortNo": 50
    }
  ],
  "functionScopes": {
    "COURSE_LEARNING": [
      {
        "type": "OWN",
        "orgId": null,
        "teamId": null
      }
    ],
    "COURSE_MANAGEMENT": [
      {
        "type": "ORG",
        "orgId": 1,
        "teamId": null
      }
    ]
  }
}
```

- `user.userId`: mã cán bộ dạng chuỗi, giữ số 0 đầu; đổi từ `user.id`.
- Hồ sơ giữ cả ID và code/name của đơn vị/nhóm, positionName, authProvider, trạng thái và snapshot nguồn. Chưa gán hoặc tham chiếu không tồn tại thì metadata trả null; không đoán tên từ BRN/BRCD/CRA. Team khác org trên hồ sơ không được dùng để hiển thị team metadata.
- `roles` và `functions`: danh sách mã duy nhất để FE kiểm tra/ẩn hiện. User không có role trả các danh sách rỗng.
- `roleGrants`: từng roleCode/roleName và scope gốc. Không ghép scope của role này cho role khác. Profile orgId/teamId không tự tạo quyền.
- `functionDetails`: danh sách màn hình đang active, hợp nhất không trùng, sắp theo sortNo/id, chứa route cho menu.
- `functionScopes`: phạm vi truy cập màn hình theo các role đã gán. Không được dùng thông tin menu để cho phép CREATE/UPDATE/DELETE; API phải kiểm tra role/scope với dữ liệu thật.
- Không trả passwordHash, JWT hoặc auth_ver trong hồ sơ.

Các trường trên nằm trong `response.data`; frontend không đọc trực tiếp từ root hoặc `authorization` cũ.

## POST /logout

Không cần request body. Yêu cầu JWT còn hiệu lực. Trả **200 OK** với BaseResponse thành công và `data: null`. Frontend xóa token và hồ sơ/quyền đã cache, chuyển về trang đăng nhập.

Backend tăng users.auth_ver bằng một atomic UPDATE có điều kiện khớp version trong JWT, đồng thời cập nhật update_time. **Logout thu hồi mọi JWT đã phát hành của tài khoản trên tất cả thiết bị**, không cần bảng session/denylist và không thay schema. Tất cả instance ứng dụng kiểm tra version từ database ở mỗi request.

Token cũ không thể gọi /me hoặc logout lại (401). Token thiếu, hết hạn hoặc không hợp lệ cũng trả 401. Frontend vẫn xóa token local khi nhận 401. Đăng nhập lại bằng mật khẩu đúng nhận token mới hoạt động bình thường.

Request đang được xử lý trước thời điểm logout có thể hoàn tất. Login diễn ra sau khi version mới đã commit được xem là phiên mới. Cơ chế này chỉ thu hồi JWT của ứng dụng, không kết thúc phiên SSO upstream.

```bash
curl -i -X POST http://localhost:8019/api/v1/auth/logout -H 'Authorization: Bearer <TOKEN>'
```
