-- Rolls back 2026-11-29-course-drafting-confirmed.sql. Roll the backend back first: the new one writes this column.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-29-course-drafting-confirmed.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_drafting_steps DROP COLUMN IF EXISTS confirmed;

COMMIT;
