-- Rollback of 2026-11-18-course-index-coverage.sql. Revisions that failed to index are forgotten (they are tried
-- again as pending) and the PDF pages of chunks are lost; re-index to restore them.
BEGIN;
SET LOCAL lock_timeout = '5s';

DELETE FROM learning_hub.course_content_index WHERE status <> 'done';
ALTER TABLE learning_hub.course_content_index DROP CONSTRAINT IF EXISTS ck_course_content_index_status;
ALTER TABLE learning_hub.course_content_index DROP COLUMN IF EXISTS error_type;
ALTER TABLE learning_hub.course_content_index DROP COLUMN IF EXISTS next_attempt_time;
ALTER TABLE learning_hub.course_content_index DROP COLUMN IF EXISTS attempts;
ALTER TABLE learning_hub.course_content_index DROP COLUMN IF EXISTS status;
ALTER TABLE learning_hub.course_content_chunks DROP COLUMN IF EXISTS page_number;

COMMIT;
