-- Course index coverage (rag-quality ticket 02): chunks carry the PDF page they came from, and a revision that fails
-- to index is recorded with its attempts and next try instead of being retried every poll forever.
-- course_content_index.status: 'done' (indexed), 'retry' (failed, will be tried again after next_attempt_time),
-- 'failed' (gave up after the configured attempts; an admin can retry it).
-- Run once on a DB that already has 2026-11-11-course-content-index.sql. Safe to run again.
-- The backend refuses to start without these columns (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-18-course-index-coverage.sql
-- Rollback: docs/sql/upgrade/2026-11-18-course-index-coverage.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL search_path TO learning_hub, public;

ALTER TABLE learning_hub.course_content_chunks ADD COLUMN IF NOT EXISTS page_number integer;
COMMENT ON COLUMN learning_hub.course_content_chunks.page_number IS
    'Trang PDF (từ 1) của đoạn khi khối là PDF hoặc tệp đính kèm có trang; NULL với khối văn bản, quy trình, video và tệp không có trang.';

ALTER TABLE learning_hub.course_content_index ADD COLUMN IF NOT EXISTS status varchar(10) NOT NULL DEFAULT 'done';
ALTER TABLE learning_hub.course_content_index ADD COLUMN IF NOT EXISTS attempts integer NOT NULL DEFAULT 0;
ALTER TABLE learning_hub.course_content_index ADD COLUMN IF NOT EXISTS next_attempt_time timestamptz;
ALTER TABLE learning_hub.course_content_index ADD COLUMN IF NOT EXISTS error_type varchar(200);
ALTER TABLE learning_hub.course_content_index DROP CONSTRAINT IF EXISTS ck_course_content_index_status;
ALTER TABLE learning_hub.course_content_index ADD CONSTRAINT ck_course_content_index_status
    CHECK (status IN ('done', 'retry', 'failed'));
COMMENT ON COLUMN learning_hub.course_content_index.status IS
    'done: đã lập chỉ mục xong; retry: lần lập chỉ mục vừa lỗi, thử lại sau next_attempt_time; failed: hết số lần thử, quản trị viên có thể thử lại.';
COMMENT ON COLUMN learning_hub.course_content_index.attempts IS 'Số lần lập chỉ mục đã lỗi liên tiếp; 0 khi status = done.';
COMMENT ON COLUMN learning_hub.course_content_index.next_attempt_time IS 'Chỉ có khi status = retry: không thử lại trước lúc này.';
COMMENT ON COLUMN learning_hub.course_content_index.error_type IS 'Tên lớp lỗi của lần thử gần nhất (không chứa nội dung khóa học).';

COMMIT;
