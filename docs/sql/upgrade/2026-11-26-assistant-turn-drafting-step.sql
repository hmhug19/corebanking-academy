-- Soạn cùng trợ lý (ADR 0002): when a drafting step is ready, the server adds an assistant message to the conversation
-- the session was started from, with a "Đã chốt" card. The turn keeps only which session and step the card is about;
-- the card's state is read live from the session. Plain id columns, no foreign keys (guideline §6); the message stays
-- when its session goes (the card is then not shown).
-- Run as dev_user (the backend's role) on a DB that already has 2026-11-10-assistant-conversations.sql. Safe to run
-- again. The backend refuses to start without these columns (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-26-assistant-turn-drafting-step.sql
-- Rollback: docs/sql/upgrade/2026-11-26-assistant-turn-drafting-step.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.assistant_turns ADD COLUMN IF NOT EXISTS drafting_session_id bigint;
ALTER TABLE learning_hub.assistant_turns ADD COLUMN IF NOT EXISTS drafting_step smallint;
COMMENT ON COLUMN learning_hub.assistant_turns.drafting_session_id IS 'Tin nhắn bước của Soạn cùng trợ lý: phiên (course_drafting_sessions) mà thẻ Đã chốt trỏ tới; null với lượt hỏi đáp thường.';
COMMENT ON COLUMN learning_hub.assistant_turns.drafting_step IS 'Bước 1 đến 4 của phiên mà tin nhắn nói tới; null với lượt hỏi đáp thường.';

COMMIT;
