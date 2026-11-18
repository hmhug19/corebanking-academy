-- Rolls back 2026-11-26-assistant-turn-drafting-step.sql. Roll the backend back first: the new one maps these columns.
-- Step messages already stored stay as plain assistant answers, without their "Đã chốt" card.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-26-assistant-turn-drafting-step.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.assistant_turns DROP COLUMN IF EXISTS drafting_step;
ALTER TABLE learning_hub.assistant_turns DROP COLUMN IF EXISTS drafting_session_id;

COMMIT;
