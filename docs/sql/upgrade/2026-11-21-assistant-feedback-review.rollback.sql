-- Rolls back 2026-11-21-assistant-feedback-review.sql: drops the shared copies (they are lost) and the
-- AI_FEEDBACK_REVIEW function with any grant. The thumbs, reasons and notes stay.
-- Deploy the previous backend first; it does not read these columns.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-21-assistant-feedback-review.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DELETE FROM learning_hub.role_functions
WHERE function_id IN (SELECT function_id FROM learning_hub.functions WHERE code = 'AI_FEEDBACK_REVIEW');
DELETE FROM learning_hub.functions WHERE code = 'AI_FEEDBACK_REVIEW';

DROP INDEX IF EXISTS learning_hub.idx_assistant_feedback_shared;
ALTER TABLE learning_hub.assistant_feedback
    DROP CONSTRAINT IF EXISTS ck_assistant_feedback_unshared_has_no_copy,
    DROP CONSTRAINT IF EXISTS ck_assistant_feedback_shared_needs_thumbs_down,
    DROP COLUMN IF EXISTS shared_retrieval_trace,
    DROP COLUMN IF EXISTS shared_prompt_version,
    DROP COLUMN IF EXISTS shared_skill,
    DROP COLUMN IF EXISTS shared_citations,
    DROP COLUMN IF EXISTS shared_answer,
    DROP COLUMN IF EXISTS shared_question,
    DROP COLUMN IF EXISTS shared_time,
    DROP COLUMN IF EXISTS shared;

COMMIT;
