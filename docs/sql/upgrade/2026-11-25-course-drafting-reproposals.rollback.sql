-- Rolls back 2026-11-25-course-drafting-reproposals.sql. Roll the backend back first: the new one reads this table.
-- Without it, "Đề xuất lại" no longer counts against the daily allowance.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-25-course-drafting-reproposals.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP TABLE IF EXISTS learning_hub.course_drafting_reproposals;

COMMIT;
