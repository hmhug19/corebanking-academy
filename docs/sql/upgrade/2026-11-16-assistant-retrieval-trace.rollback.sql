-- Rolls back 2026-11-16-assistant-retrieval-trace.sql: drops the retrieval trace column (the traces are lost).
-- Deploy the previous backend first; it does not read the column.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-16-assistant-retrieval-trace.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.assistant_turns DROP COLUMN IF EXISTS retrieval_trace;

COMMIT;
