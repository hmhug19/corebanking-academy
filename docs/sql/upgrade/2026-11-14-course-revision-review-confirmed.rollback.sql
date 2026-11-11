-- Rollback of 2026-11-14-course-revision-review-confirmed.sql. Deploy a backend without the columns first; who confirmed
-- and when is lost (the audit log keeps it for each publish).
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_revisions DROP COLUMN IF EXISTS review_confirmed_at;
ALTER TABLE learning_hub.course_revisions DROP COLUMN IF EXISTS review_confirmed_by;

COMMIT;
