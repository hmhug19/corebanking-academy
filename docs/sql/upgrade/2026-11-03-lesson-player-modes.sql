-- Lesson player modes: mốc (chương) và transcript của khối video, lưu dạng jsonb ngay trên lesson_blocks.
-- Chạy một lần trên DB đã có baseline Learning (DB mới nhận hai cột từ learning_hub_learning.sql).
-- Chạy lại an toàn: chỉ thêm cột còn thiếu, không đổi dữ liệu.
-- Backend từ chối khởi động nếu thiếu cột (ddl-auto=validate): chạy TRƯỚC khi triển khai backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-03-lesson-player-modes.sql
-- Rollback: docs/sql/upgrade/2026-11-03-lesson-player-modes.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.lesson_blocks ADD COLUMN IF NOT EXISTS video_chapters jsonb;
ALTER TABLE learning_hub.lesson_blocks ADD COLUMN IF NOT EXISTS transcript jsonb;

COMMENT ON COLUMN learning_hub.lesson_blocks.video_chapters IS 'Mốc của khối video: mảng JSON [{"startSeconds": số giây, "title": tên mốc}], tối đa 50 mốc, startSeconds tăng dần; NULL khi không có mốc hoặc khối không phải video. Service kiểm tra, không có ràng buộc DB.';
COMMENT ON COLUMN learning_hub.lesson_blocks.transcript IS 'Transcript của khối video: mảng JSON [{"startSeconds": số giây, "text": nội dung}], tối đa 2000 đoạn, startSeconds tăng dần; NULL khi không có transcript hoặc khối không phải video. Service kiểm tra, không có ràng buộc DB.';

COMMIT;
