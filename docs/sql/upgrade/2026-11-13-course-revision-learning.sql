-- Run as the owner of the assessment tables (the provisioned migration role).
-- Stop old backend instances, apply, then deploy matching backend/frontend. No data is deleted.
BEGIN;
DROP INDEX IF EXISTS learning_hub.ux_asm_quizzes_final;
CREATE INDEX IF NOT EXISTS ix_asm_quizzes_final ON learning_hub.quizzes(course_id)
    WHERE quiz_type = 'FINAL_EXAM' AND NOT is_deleted;
DROP INDEX IF EXISTS learning_hub.ux_asm_quiz_attempts_running;
CREATE UNIQUE INDEX ux_asm_quiz_attempts_running
    ON learning_hub.quiz_attempts(quiz_id, user_id, enrollment_id) WHERE status = 'IN_PROGRESS';
COMMIT;
