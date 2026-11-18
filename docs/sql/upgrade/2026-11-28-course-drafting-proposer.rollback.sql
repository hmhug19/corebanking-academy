-- Rolls back 2026-11-28-course-drafting-proposer.sql. Roll the backend back first: the new one reads these columns.
-- Sessions a Trợ lý ngoài drove stay but look like in-house ones; the old worker would then try them and fail them,
-- as they have no source document: stop or finish them first.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-28-course-drafting-proposer.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_drafting_sessions DROP CONSTRAINT IF EXISTS ck_course_drafting_sessions_proposer;
ALTER TABLE learning_hub.course_drafting_sessions
    DROP COLUMN IF EXISTS source_name,
    DROP COLUMN IF EXISTS proposer;

COMMIT;
