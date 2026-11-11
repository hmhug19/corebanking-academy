-- Rollback of 2026-11-15-course-job-soft-delete.sql. Deploy a backend that deletes job rows again first. Jobs already
-- marked deleted come back as ordinary cancelled/finished jobs without steps, so remove them first if they must not show.
BEGIN;
SET LOCAL lock_timeout = '5s';

DELETE FROM learning_hub.course_generation_jobs WHERE deleted_at IS NOT NULL;
ALTER TABLE learning_hub.course_generation_jobs DROP COLUMN IF EXISTS deleted_at;

COMMIT;
