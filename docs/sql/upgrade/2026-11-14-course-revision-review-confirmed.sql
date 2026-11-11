-- Records who confirmed they reviewed an AI-generated revision, and when, as the condition to publish it.
-- Revisions that are not AI generated leave both columns empty. Existing rows stay empty.
-- Run once on a DB that already has 2026-11-13-course-revision-ai-generated.sql. Safe to run again.
-- The backend refuses to start without these columns (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-14-course-revision-review-confirmed.sql
-- Rollback: docs/sql/upgrade/2026-11-14-course-revision-review-confirmed.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_revisions ADD COLUMN IF NOT EXISTS review_confirmed_by varchar(20);
ALTER TABLE learning_hub.course_revisions ADD COLUMN IF NOT EXISTS review_confirmed_at timestamptz;
COMMENT ON COLUMN learning_hub.course_revisions.review_confirmed_by IS
    'Người xác nhận đã rà soát nội dung do AI tạo khi xuất bản bản này.';
COMMENT ON COLUMN learning_hub.course_revisions.review_confirmed_at IS
    'Thời điểm xác nhận đã rà soát nội dung do AI tạo khi xuất bản bản này.';

COMMIT;
