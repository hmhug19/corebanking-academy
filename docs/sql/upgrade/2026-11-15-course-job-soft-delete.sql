-- Course generation jobs are no longer removed when the author deletes them: the row stays, marked deleted, so the daily
-- limit on AI course jobs still counts it. Steps and the source document are still removed.
-- Run once on a DB that already has 2026-11-12-course-generation-jobs.sql. Safe to run again.
-- The backend refuses to start without this column if it validates the schema, and queries it at the first request: run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-15-course-job-soft-delete.sql
-- Rollback: docs/sql/upgrade/2026-11-15-course-job-soft-delete.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_generation_jobs ADD COLUMN IF NOT EXISTS deleted_at timestamptz;
COMMENT ON COLUMN learning_hub.course_generation_jobs.deleted_at IS
    'Thời điểm tác giả xóa job; dòng được giữ lại để hạn mức tạo khóa học bằng AI trong ngày vẫn tính job này.';

COMMIT;
