-- Rollback of 2026-11-11-assistant-conversation-lesson-context.sql. Drops the lesson context of conversations (the
-- conversations and their turns stay). Deploy a backend without the lesson context first.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-11-assistant-conversation-lesson-context.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.assistant_conversations DROP CONSTRAINT IF EXISTS ck_assistant_conversations_context;
ALTER TABLE learning_hub.assistant_conversations DROP COLUMN IF EXISTS context_label;
ALTER TABLE learning_hub.assistant_conversations DROP COLUMN IF EXISTS context_lesson_id;
ALTER TABLE learning_hub.assistant_conversations DROP COLUMN IF EXISTS context_revision_id;
ALTER TABLE learning_hub.assistant_conversations DROP COLUMN IF EXISTS context_course_id;

COMMIT;
