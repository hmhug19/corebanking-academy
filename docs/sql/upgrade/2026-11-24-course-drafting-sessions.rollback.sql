-- Rolls back 2026-11-24-course-drafting-sessions.sql. Roll the backend back first: the new one reads these tables.
-- Draft courses already created from a session stay; their source documents (course_source) stay until deleted.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-24-course-drafting-sessions.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP TABLE IF EXISTS learning_hub.course_drafting_steps;
DROP TABLE IF EXISTS learning_hub.course_drafting_sessions;

COMMIT;
