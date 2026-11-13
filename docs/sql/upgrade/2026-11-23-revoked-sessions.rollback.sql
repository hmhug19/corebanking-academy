-- Rolls back 2026-11-23-revoked-sessions.sql. Roll the backend back first: the new one reads this table.
-- Sessions logged out since the upgrade become valid again until their tokens expire.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-23-revoked-sessions.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE IF EXISTS learning_hub.scorm_registrations DROP COLUMN IF EXISTS launch_session_id;
DROP TABLE IF EXISTS learning_hub.revoked_sessions;

COMMIT;
