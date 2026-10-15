# Identity / Organization / RBAC compact

`learning_hub_identity.sql` là bản sao SQL compact do người dùng cung cấp, gồm 8 bảng: orgs, teams, users, roles, functions, role_functions, user_roles, audit_log. PostgreSQL 14+, schema learning_hub. Chỉ dùng để tạo mới; không chạy đè schema đang có dữ liệu. Flyway đang tắt, Hibernate ddl-auto=validate. Không có migration hoặc thay đổi database tự động trong bước cập nhật source.

## Dữ liệu và quan hệ

- users.user_id là mã cán bộ dạng chuỗi; user_name duy nhất. Login hỗ trợ cả hai, không phân biệt hoa/thường cho username và từ chối định danh khớp nhiều user.
- LOCAL dùng users.password_hash BCrypt; SSO để NULL và không dùng API login mật khẩu local. Không trả hash trong /me.
- Một org_id/team_id chính, position_name là text. Không tự suy quan hệ cây từ BRN/BRCD/CRA.
- Không có FOREIGN KEY vật lý. Khi xây dựng API quản trị phải kiểm tra các tham chiếu logic, team thuộc org và ngăn chu trình parent_id.
- functions là màn hình FE; role_functions không cấp quyền thao tác ghi cho API. Backend kiểm tra role và scope của cùng user_roles grant; scope GLOBAL/ORG/TEAM/OWN.
- Role/Function không có timestamp; Team chỉ có create_time; role_functions dùng created_at. update_time ở các entity có cột này được JPA cập nhật khi ghi.

## Chạy script

Dùng SQL Editor trên database mới với quyền tạo schema/bảng. Script chính BEGIN/COMMIT tạo bảng và seed bốn role cùng màn hình. Không chạy lại khi bảng đã tồn tại.

Phần SUPER_ADMIN ở sau COMMIT là thao tác gán quyền riêng, chứa tham số `:bootstrap_user_id`: cần bind đúng mã cán bộ của tài khoản đã tồn tại trong SQL client trước khi chạy. Không gửi nguyên file với tham số chưa bind qua JDBC/psql. Vì không có FK, SQL không tự phát hiện gán quyền cho user không tồn tại. Phần cuối không nằm trong transaction tạo schema; kiểm tra kết quả hai phần riêng biệt. Script không tạo user và không reset password.

Khi đổi schema của database có dữ liệu, cần một migration riêng được kiểm tra trước; bản SQL này không phải migration.

## Kiểm tra source

`mvn clean verify` kiểm tra đủ 8 bảng, column/type/length/primary key cùng các luồng login và role/scope. `/api/v1/auth/me` trả `roles`, `functions`, `roleGrants`, `functionDetails`, `functionScopes`; frontend cần dùng contract mới thay cho permissions/permissionScopes.
