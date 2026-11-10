-- Rollback of 2026-11-10-assistant-feedback.sql. Drops the table and with it every 👍/👎 users gave to answers.
-- It prints how many rows the table holds first, so the loss is visible in the psql output.
-- Deploy a backend without feedback first: it fails on startup once the table is gone.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-10-assistant-feedback.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $report$
DECLARE
    feedback_rows bigint := 0;
BEGIN
    IF to_regclass('learning_hub.assistant_feedback') IS NOT NULL THEN
        SELECT count(*) INTO feedback_rows FROM learning_hub.assistant_feedback;
    END IF;
    RAISE NOTICE 'Dropping assistant_feedback (% rows)', feedback_rows;
END
$report$;

DROP INDEX IF EXISTS learning_hub.idx_assistant_feedback_owner;
DROP TABLE IF EXISTS learning_hub.assistant_feedback;

COMMIT;
