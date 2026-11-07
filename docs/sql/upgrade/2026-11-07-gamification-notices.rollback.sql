-- Rollback of 2026-11-07-gamification-notices.sql: deletes every badge and level notice, then restores the table. The
-- deadline reminders are untouched.
BEGIN;
SET LOCAL lock_timeout = '5s';
DELETE FROM learning_hub.learning_reminders WHERE kind <> 'DEADLINE';
DROP INDEX IF EXISTS learning_hub.idx_learning_reminders_notice;
ALTER TABLE learning_hub.learning_reminders DROP CONSTRAINT IF EXISTS ck_learning_reminders_kind;
ALTER TABLE learning_hub.learning_reminders DROP COLUMN IF EXISTS subject;
ALTER TABLE learning_hub.learning_reminders DROP COLUMN IF EXISTS user_id;
ALTER TABLE learning_hub.learning_reminders DROP COLUMN IF EXISTS kind;
ALTER TABLE learning_hub.learning_reminders ALTER COLUMN recipient_id SET NOT NULL;
COMMIT;
