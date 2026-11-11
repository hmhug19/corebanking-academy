-- Rollback of 2026-11-13-course-revision-ai-generated.sql. Deploy a backend without the column first; the flag is lost.
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_revisions DROP COLUMN IF EXISTS ai_generated;

COMMIT;
