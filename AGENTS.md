# Hướng dẫn AI Coding Agent

Áp dụng cho toàn repository, với Codex, Claude và các coding agent khác.

**Trước mọi nhiệm vụ liên quan source code, phải đọc và tuân thủ [DEVELOPMENT_GUIDELINES.md](DEVELOPMENT_GUIDELINES.md)**, sau đó đọc hướng dẫn thư mục con và tài liệu chuyên đề liên quan. Guideline là nguồn chung cho quy trình, kiến trúc/convention Java, style, API, database và bảo mật; không sao chép các quy tắc đó sang file agent khác.

## Nguyên tắc ưu tiên

- Đúng nghiệp vụ trước, tối ưu sau.
- Đơn giản, dễ hiểu và dễ maintain.
- Tuân thủ kiến trúc và convention hiện tại.
- Hạn chế thay đổi ngoài phạm vi.
- Quan tâm hiệu năng, bảo mật và regression.
- Luôn kiểm tra trước khi kết luận hoàn thành; báo đúng bước đã chạy, kết quả và giới hạn.

## Hướng dẫn quan trọng cần giữ

- Yêu cầu trực tiếp của người dùng được ưu tiên so với quy ước repository, trong giới hạn chỉ dẫn hệ thống/công cụ. Xác minh source/config khi tài liệu lệch; chỉ hỏi khi thiếu quyết định quan trọng hoặc vượt phạm vi đã giao.
- Giữ thay đổi của người dùng; không reset/clean/checkout để làm sạch workspace. Không tự commit/push/deploy hoặc sửa dữ liệu/schema server khi chưa được yêu cầu.
- Không lộ secret; không tự seed/reset password/cấp quyền để kiểm tra pass. Chỉ kiểm tra API thật bằng tài khoản/dữ liệu đã được cho phép.
- Không tự sinh thêm file test vào source repository; chỉ thêm khi người dùng yêu cầu rõ ràng. Chạy kiểm tra hiện có/phù hợp; build thành công không đồng nghĩa test pass.
- Cập nhật hướng dẫn theo phạm vi trong guideline hoặc tài liệu chuyên đề hiện có; không tạo thêm Markdown trùng lặp. Contract Identity và phạm vi UI hiện tại được dẫn tại mục 13 của guideline.

## Quy tắc database dùng chung

- PostgreSQL dùng chung cho phát triển: database `appdb`, schema `learning_hub`; backend runtime kết nối bằng role `dev_user` (`DB_USER=dev_user`). Không tự đổi sang `app_user` hoặc role khác.
- Migration và thao tác tạo/sửa object trong schema phải bảo đảm `dev_user` có quyền cần thiết; ưu tiên chạy migration bằng `dev_user`, tránh tạo bảng/sequence thuộc role khác khiến backend không truy cập được.
- Mật khẩu chỉ lấy từ secret/env đã provision trên môi trường chạy; không ghi credential thật vào source, tài liệu, log hoặc output.
