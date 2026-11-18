-- Rollback của 2026-11-24-video-allow-seeking.sql. Xóa cột allow_seeking: mọi khối video đang cho tua quay về không cho tua.
-- Triển khai backend không còn cột này trước: backend hiện tại không khởi động được khi cột đã bị xóa.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-24-video-allow-seeking.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.lesson_blocks DROP COLUMN IF EXISTS allow_seeking;

COMMIT;
