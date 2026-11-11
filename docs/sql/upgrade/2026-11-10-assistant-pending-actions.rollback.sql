-- Rollback of 2026-11-10-assistant-pending-actions.sql. Drops the table and with it every proposed change that was
-- still waiting and the recorded outcome of the ones answered.
-- It prints how many rows the table holds first, so the loss is visible in the psql output.
-- Deploy a backend without database-backed pending actions first: it fails on startup once the table is gone.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-10-assistant-pending-actions.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $report$
DECLARE
    action_rows bigint := 0;
BEGIN
    IF to_regclass('learning_hub.assistant_pending_actions') IS NOT NULL THEN
        SELECT count(*) INTO action_rows FROM learning_hub.assistant_pending_actions;
    END IF;
    RAISE NOTICE 'Dropping assistant_pending_actions (% rows)', action_rows;
END
$report$;

DROP INDEX IF EXISTS learning_hub.idx_assistant_pending_actions_created;
DROP INDEX IF EXISTS learning_hub.idx_assistant_pending_actions_owner;
DROP TABLE IF EXISTS learning_hub.assistant_pending_actions;

COMMIT;
