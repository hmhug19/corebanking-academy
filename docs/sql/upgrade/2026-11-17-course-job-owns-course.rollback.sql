-- Rollback of 2026-11-17-course-job-owns-course.sql. Stopped jobs become cancelled; the AI state of lessons is lost.
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP INDEX IF EXISTS learning_hub.idx_course_generation_jobs_revision;
UPDATE learning_hub.course_generation_jobs SET status = 'cancelled' WHERE status = 'stopped';
ALTER TABLE learning_hub.course_generation_jobs DROP CONSTRAINT IF EXISTS course_generation_jobs_status_check;
ALTER TABLE learning_hub.course_generation_jobs ADD CONSTRAINT course_generation_jobs_status_check CHECK (
    status IN ('queued', 'running', 'waiting_approval', 'completed', 'failed', 'cancelled'));
ALTER TABLE learning_hub.course_generation_jobs DROP COLUMN IF EXISTS revision_id;
ALTER TABLE learning_hub.course_generation_jobs DROP COLUMN IF EXISTS course_id;
ALTER TABLE learning_hub.course_lessons DROP CONSTRAINT IF EXISTS ck_course_lessons_ai_state;
ALTER TABLE learning_hub.course_lessons DROP COLUMN IF EXISTS ai_state;

COMMIT;
