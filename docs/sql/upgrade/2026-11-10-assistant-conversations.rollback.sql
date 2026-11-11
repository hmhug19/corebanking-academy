-- Rollback of 2026-11-10-assistant-conversations.sql. Drops both tables and with them every saved conversation.
-- It prints how many rows each table holds first, so the loss is visible in the psql output.
-- Deploy a backend without conversations first: it fails on startup once the tables are gone.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-10-assistant-conversations.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $report$
DECLARE
    conversation_rows bigint := 0;
    turn_rows bigint := 0;
BEGIN
    IF to_regclass('learning_hub.assistant_conversations') IS NOT NULL THEN
        SELECT count(*) INTO conversation_rows FROM learning_hub.assistant_conversations;
    END IF;
    IF to_regclass('learning_hub.assistant_turns') IS NOT NULL THEN
        SELECT count(*) INTO turn_rows FROM learning_hub.assistant_turns;
    END IF;
    RAISE NOTICE 'Dropping assistant_conversations (% rows) and assistant_turns (% rows)', conversation_rows, turn_rows;
END
$report$;

DROP INDEX IF EXISTS learning_hub.idx_assistant_turns_conversation;
DROP TABLE IF EXISTS learning_hub.assistant_turns;
DROP INDEX IF EXISTS learning_hub.idx_assistant_conversations_activity;
DROP INDEX IF EXISTS learning_hub.idx_assistant_conversations_owner;
DROP TABLE IF EXISTS learning_hub.assistant_conversations;

COMMIT;
