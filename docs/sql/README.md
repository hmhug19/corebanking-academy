# Khởi tạo PostgreSQL cho Learning Hub

`learning_hub_identity.sql` tạo 13 bảng trong schema `learning_hub`, độc lập với Flyway và các bảng hiện có trong `public`. Chạy trên PostgreSQL 14 trở lên, trong database đích đã tồn tại, với tài khoản có quyền tạo schema, bảng và function. File lưu UTF-8.

Ví dụ chạy bằng psql (thay thông tin kết nối theo môi trường):

```powershell
psql -h localhost -p 5432 -U postgres -d learning_hub_db -v ON_ERROR_STOP=1 -f docs/sql/learning_hub_identity.sql
```

Hoặc mở file trong SQL Editor của pgAdmin/DBeaver, chọn đúng database và chạy toàn bộ script. Nếu gặp lỗi, rollback transaction trước khi chạy lại. Không chạy từng đoạn rồi commit riêng.

Script không tạo database. Nếu cần database riêng, chạy lệnh sau riêng biệt, ngoài transaction, bằng tài khoản có quyền `CREATEDB`:

```sql
CREATE DATABASE learning_hub_db ENCODING 'UTF8';
```

Script tạo mới một lần, không xóa dữ liệu và không bỏ qua các bảng trùng tên: nếu một bảng đã tồn tại, toàn bộ transaction thất bại. Không dùng script này làm migration cho schema đang có dữ liệu.

| Bảng | Mục đích |
|---|---|
| users | Hồ sơ và snapshot dữ liệu từ hệ thống xác thực |
| user_credentials | Hash mật khẩu đăng nhập cục bộ, liên kết 1–1 với user |
| orgs | Cây đơn vị/phòng, cha–con được cấu hình sau |
| user_orgs | Thành viên tổ chức, tối đa một tổ chức chính đang active |
| teams | Nhóm công tác thuộc tổ chức |
| user_teams | Thành viên nhóm, tối đa một nhóm chính đang active |
| positions | Danh mục chức danh |
| user_pos | Chức danh của user tại tổ chức hoặc nhóm, hỗ trợ kiêm nhiệm |
| roles | Vai trò ứng dụng Learning Hub |
| permission | Quyền chức năng |
| role_permission | Quyền của vai trò |
| user_roles | Vai trò và phạm vi của người dùng |
| audit_log | Nhật ký do backend ghi |

Tên bảng ngắn được dùng thay cho `organizations`, `user_organizations`, `user_position_assignments` và `user_role_assignments`. Các tên liên quan đến quyền viết đầy đủ: `permission`, `permission_id` và `role_permission`.

## Quy tắc dữ liệu

- `user_id` là chuỗi 20 ký tự từ `userInfo.userID`, giữ được số 0 đầu. Không có mật khẩu hay token của hệ thống xác thực trong các bảng này.
- `user_name` có trong cả `users` và `user_credentials`, ví dụ `hunghm8`; `user_id` là mã cán bộ, ví dụ `183617`. Username bắt buộc, duy nhất và lưu chữ thường, không có khoảng trắng đầu/cuối. Khóa ngoại ghép `(user_id, user_name)` đảm bảo credentials khớp đúng user; đổi username tại `users` sẽ tự cập nhật credentials bằng `ON UPDATE CASCADE`. Khóa chính/UNIQUE đã tạo index cho tra cứu theo cả hai định danh.
- Đăng nhập cục bộ có thể tra theo `user_id` hoặc `user_name`, dùng `password_hash` do backend tạo bằng BCrypt/PasswordEncoder và kiểm tra bằng `PasswordEncoder.matches()`. Backend chuẩn hóa username thành chữ thường và phải từ chối nếu đầu vào đồng thời khớp mã cán bộ của user này và username của user khác; kiểm tra tránh xung đột này khi tạo/đổi username. Hai ràng buộc UNIQUE riêng không ngăn xung đột giữa hai loại định danh. Chưa thay đổi source đăng nhập trong bước tạo DDL này.
- Không lưu mật khẩu gốc, không trả hash qua API hồ sơ và không ghi hash vào audit. User chỉ xác thực qua hệ thống nguồn không cần bản ghi credentials; sự vắng mặt của bản ghi này không được tự kích hoạt đăng nhập cục bộ.
- Khi đổi/reset mật khẩu, backend cập nhật `password_changed_time` và tăng `users.auth_ver` nếu cần thu hồi phiên, trong cùng transaction. Trigger chỉ tự cập nhật `update_time`. Backend vẫn phải kiểm tra `users.status` trước khi cho đăng nhập hoặc sử dụng token.
- BRN/BRCD/CRA và tên tương ứng là snapshot nguồn trên user. `deptCode`, BDS và mã chi nhánh nhân sự được giữ riêng; không mặc định chúng đồng nhất với nhau. `last_sync_time` ghi lần đồng bộ gần nhất.
- Cây chính thức nằm ở `orgs.parent_id`. Chưa áp đặt thứ tự BRN → BRCD → CRA. Mã tổ chức chỉ duy nhất trong cùng cha và loại; mã gốc duy nhất theo loại. Backend cần ngăn chu trình nhiều cấp, xác nhận đúng cha–con khi đồng bộ, và xác định chính sách kế thừa phạm vi xuống tổ chức con.
- User có thể thuộc nhiều tổ chức/nhóm và giữ nhiều chức danh. Quan hệ membership không tự cấp quyền. Backend xác nhận membership phù hợp khi gán chức danh và quyền; schema không bắt buộc người quản trị được ủy quyền phải là thành viên của tổ chức đó.
- `teams.org_id` xác định tổ chức quản lý nhóm. Tên/mã nhóm DEP, LOAN, CHANNEL, CORE, INTEGRATION được nhập sau khi có tổ chức; không seed nhóm vào một đơn vị giả định.
- `user_pos.team_id` và `user_roles.team_id`, nếu có, bắt buộc thuộc đúng `org_id` nhờ khóa ngoại ghép.
- Chức danh công tác khác vai trò ứng dụng. `roleInfo` và `functionInfo` nguồn không tự được chuyển thành quyền Learning Hub.
- Phạm vi quyền: `GLOBAL`, `ORG`, `TEAM`, `OWN`. Source đã mapping schema này; `OWN` cần owner ID do backend cung cấp, quyền được giao học cần bổ sung ở module learning.
- Vai trò/chức danh là danh mục dùng chung. Backend phải yêu cầu quyền GLOBAL khi sửa danh mục, ngăn admin đơn vị cấp GLOBAL hoặc cấp phạm vi/quyền vượt quyền của mình, kiểm tra cả user đích và scope đích. DDL không tự thực thi các chính sách này.
- Không xóa dây chuyền. Khóa ngoại dùng `RESTRICT`; chuyển trạng thái hoặc `active` khi ngừng sử dụng. Khi gán lại role/chức danh/membership, kích hoạt lại bản ghi cũ để tránh trùng khóa.
- Seed gồm 9 chức danh, 4 vai trò và 21 quyền cho giai đoạn identity/quản trị, không có user thật hoặc assignment. Quyền giảng viên/học viên với khóa học được bổ sung ở giai đoạn learning.
- `auth_ver` không tự tăng; backend sẽ tăng khi thu hồi phiên theo chính sách. `audit_log` không tự ghi; backend ghi audit trong transaction cùng thay đổi nghiệp vụ.
- `timestamptz` lưu thời điểm có múi giờ; có thể dùng `SET TIME ZONE 'Asia/Ho_Chi_Minh';` cho session hiển thị. Trigger tự cập nhật `update_time` bằng thời điểm bắt đầu transaction.
- `varchar(n)` giới hạn số ký tự PostgreSQL, khác `VARCHAR2(n BYTE)` của Oracle.

## Kiểm tra sau khi chạy

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'learning_hub' AND table_type = 'BASE TABLE'
ORDER BY table_name;

SELECT code, name FROM learning_hub.roles ORDER BY code;
SELECT code, name FROM learning_hub.positions ORDER BY code;

SELECT count(*) AS trigger_count
FROM information_schema.triggers
WHERE trigger_schema = 'learning_hub' AND trigger_name = 'trg_touch_time';
-- Kỳ vọng: 13 bảng, 4 vai trò, 9 chức danh, 11 trigger UPDATE.
```

Chỉ tạo bảng và dữ liệu danh mục chưa cấp quyền truy cập dữ liệu cho người dùng ứng dụng. Backend phải kiểm tra authorization trước mỗi API; nếu triển khai truy cập trực tiếp database thì cần thiết kế database roles/RLS riêng.
