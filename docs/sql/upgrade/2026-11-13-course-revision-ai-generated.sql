-- Marks course revisions whose content was written by the AI course generation, so the editor can say so and publishing
-- can ask the author to confirm they reviewed it. Existing revisions are not AI generated.
-- Run once on a DB that already has the Learning baseline. Safe to run again.
-- The backend refuses to start without this column (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-13-course-revision-ai-generated.sql
-- Rollback: docs/sql/upgrade/2026-11-13-course-revision-ai-generated.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_revisions ADD COLUMN IF NOT EXISTS ai_generated boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN learning_hub.course_revisions.ai_generated IS
    'Nội dung do AI tạo; bản nháp sao từ bản này giữ cờ, và xuất bản cần người dùng xác nhận đã rà soát.';

COMMIT;
