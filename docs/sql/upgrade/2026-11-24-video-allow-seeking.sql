-- Cho phép tua video: cờ allow_seeking trên lesson_blocks (mặc định false; khối cũ đọc false).
-- Chỉ khối video mới được đặt true; service kiểm tra. Không đổi cách tính hoàn thành (chỉ đoạn đã phát được tính).
-- Chạy TRƯỚC khi triển khai backend: ddl-auto=validate từ chối khởi động nếu thiếu cột. Chạy lại an toàn.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-24-video-allow-seeking.sql
-- Rollback: docs/sql/upgrade/2026-11-24-video-allow-seeking.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.lesson_blocks ADD COLUMN IF NOT EXISTS allow_seeking boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN learning_hub.lesson_blocks.allow_seeking IS 'Khối video cho phép người học tua tự do (mặc định false); luôn false với khối khác, service kiểm tra. Không đổi cách tính hoàn thành: chỉ đoạn đã phát được tính.';

COMMIT;
