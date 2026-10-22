# Khởi tạo Identity / Organization / RBAC

## Bổ sung module Learning

Backend hiện có thêm 36 API Learning và 26 bảng được định nghĩa tại
[learning_hub_learning.sql](learning_hub_learning.sql). Review và chạy file Learning **một lần sau Identity**
trên môi trường được phép, trước khi chạy bản ứng dụng có entity Learning. File không có FK/REFERENCES,
không seed/reset/cấp quyền và không sửa 8 bảng Identity. Không chạy lại trên schema đã có các bảng Learning;
nếu đã có dữ liệu cần kế hoạch nâng cấp riêng. Giữ ddl-auto=validate và Flyway off.

Contract và hướng dẫn từng bảng: [LEARNING.md](../api/LEARNING.md).
Request mẫu: [cURL](../api/LEARNING_CURL.md), [Postman](../postman/learning.postman_collection.json).
SQL Identity dưới đây vẫn là nguồn khởi tạo duy nhất của **cụm Identity**, không thay bằng SQL Learning.

## Bổ sung kho tri thức "Trợ lý AI"

[learning_hub_knowledge.sql](learning_hub_knowledge.sql) tạo `knowledge_documents` (đồng thời là hàng đợi xử lý nền)
và `knowledge_document_chunks` (đoạn văn bản kèm vector `vector(1024)` của bge-m3, chỉ mục HNSW cosine). Cần
PostgreSQL có extension pgvector (local: image `pgvector/pgvector:pg16` trong `compose.yaml`); file tự chạy
`CREATE EXTENSION IF NOT EXISTS vector` nên tài khoản chạy script cần quyền tạo extension. Thứ tự chạy trên database
mới: Identity → Learning → Knowledge, mỗi file một lần. Không FK/REFERENCES, không DROP, không seed; giữ
ddl-auto=validate và Flyway off. Chức năng `AI_ASSISTANT` nằm trong file Identity.
Contract: [KNOWLEDGE.md](../api/KNOWLEDGE.md).

## Identity / Organization / RBAC

Nguồn SQL duy nhất: [learning_hub_identity.sql](learning_hub_identity.sql), PostgreSQL 14+. Chạy toàn bộ file một lần trong database/schema mới hoặc vừa được tạo lại. Không cần script upgrade, gán root, extension pgcrypto, tham số bind hoặc Flyway. File không tạo/xóa database và không tự DROP dữ liệu; không chạy đè database đang có dữ liệu cần giữ.

## Nội dung

- 8 bảng: orgs, teams, users, roles, functions, role_functions, user_roles, audit_log; kèm comment cho từng bảng/cột và 12 index.
- 5 vai trò: SUPER_ADMIN, ADMIN, LEARNER, INSTRUCTOR, REPORT_VIEWER; 17 chức năng (gồm AI_ASSISTANT, route /assistant, cấp cho mọi vai trò) và mapping màn hình hiện có. Danh mục màn hình không có nghĩa backend đã triển khai API khóa học/thi/báo cáo.
- Một tổ chức gốc trong phạm vi demo: BIDV110B803 — Ban Quản lý và phát triển Core Banking (UNIT). 15 nhóm/bộ phận giữ nguyên mã và tên từ dữ liệu nguồn; gồm Ban Giám đốc, BA&QC, phát triển Core/Kênh, tích hợp, quản trị và hỗ trợ. Không tạo phòng trung gian hoặc node cha ngoài phạm vi; HO/WORK_LOGIC là loại nguồn, không thay trực tiếp org_type nội bộ.
- 7 tài khoản LOCAL, 8 phân quyền theo đúng cặp role/scope và một audit khởi tạo. ID số tự tăng được database sinh; dữ liệu tham chiếu bằng mã, không cần chỉnh sequence.
- Không có FOREIGN KEY vật lý. Service kiểm tra quan hệ logic, cây active, org/team và scope. users.must_change_password đã nằm trong schema; không cần nâng cột sau tạo.

## Đăng nhập demo

Mật khẩu công khai của bộ dữ liệu demo: **Demo@123**. Có thể đăng nhập bằng mã cán bộ hoặc username dưới đây; tất cả tài khoản seed có must_change_password=false, không bắt buộc đổi mật khẩu lần đầu. Mật khẩu mới từ 6 ký tự và tối đa 72 byte UTF-8. Đây là dữ liệu tổng hợp được yêu cầu cho demo, không dùng tài khoản hoặc mật khẩu thật.

| Mã cán bộ | Username | Vai trò / phạm vi | Tổ chức / nhóm |
|---|---|---|---|
| 000001 | super.admin | SUPER_ADMIN / GLOBAL | Ban Core Banking / chưa gán nhóm |
| 000002 | quantri | ADMIN / GLOBAL | Ban Core Banking / chưa gán nhóm |
| 000003 | quantri.ban | ADMIN / ORG BIDV110B803 | Ban Core Banking / chưa gán nhóm |
| 000004 | hocvien | LEARNER / OWN | Nhóm BA&QC Tiền gửi — BIDV110B803103 |
| 000005 | giangvien | INSTRUCTOR / ORG BIDV110B803 và LEARNER / OWN | Nhóm phát triển Core Banking — BIDV110B8031281 |
| 000006 | baocao | REPORT_VIEWER / TEAM BIDV110B8031000 | Ban Giám đốc — BIDV110B8031000 |
| 000007 | hocvien.tichhop | LEARNER / OWN | Nhóm Tích hợp Core Banking — BIDV110B8031280 |

1. Chạy script bằng SQL client trên database mới, với quyền tạo schema, bảng và index.
2. Cấu hình DB_URL/DB_USER/DB_PASSWORD, JWT_SECRET trong .env hiện tại. Giữ ddl-auto=validate và Flyway off. Nếu runtime DB user khác chủ schema, DBA cấp quyền schema/bảng/sequence theo hướng dẫn triển khai.
3. Để trống ba biến APP_BOOTSTRAP_ADMIN_ID/USER/PASSWORD vì script đã tạo root. Bootstrap Java vẫn giữ cho trường hợp khởi tạo có chủ đích; khi có root active thì không tạo thêm hoặc reset quyền/mật khẩu.
4. Đăng nhập super.admin hoặc 000001 bằng mật khẩu demo. /auth/me trả mustChangePassword=false và quyền SUPER_ADMIN; có thể dùng ngay các API quản trị. Tài khoản LOCAL tạo mới qua API hoặc root tạo qua bootstrap vẫn phải đổi mật khẩu tạm như logic hiện tại.
5. quantri.ban quản lý toàn Ban gồm 15 nhóm và các tài khoản thường trong Ban; hocvien.tichhop là học viên nhóm tích hợp, không phải user ngoài đơn vị. Quyền TEAM của baocao chỉ gắn với Ban Giám đốc; tên bộ phận/chức danh không tự cấp quyền quản trị. Chỉ SUPER_ADMIN quản lý tài khoản đang mang vai trò quản trị.

Schema/seed cùng một transaction PostgreSQL; có lỗi thì rollback transaction trước khi chạy lại. Database đã có dữ liệu cần giữ phải có kế hoạch thay đổi riêng, không dùng file khởi tạo để nâng cấp hoặc reset. Không có script nào tự chạy khi ứng dụng khởi động.
