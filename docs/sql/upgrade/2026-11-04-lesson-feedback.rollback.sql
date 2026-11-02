-- Rollback of 2026-11-04-lesson-feedback.sql. Drops the table and with it every learner's rating, comment, question and
-- report. It prints how many rows the table holds first, so the loss is visible in the psql output.
-- Deploy a backend without lesson feedback first: it fails on startup once the table is gone.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-04-lesson-feedback.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $report$
DECLARE
    feedback_rows bigint := 0;
BEGIN
    IF to_regclass('learning_hub.lesson_feedback') IS NOT NULL THEN
        SELECT count(*) INTO feedback_rows FROM learning_hub.lesson_feedback;
    END IF;
    RAISE NOTICE 'Dropping lesson_feedback (% rows)', feedback_rows;
END
$report$;

DROP INDEX IF EXISTS learning_hub.idx_lesson_feedback_lesson_kind;
DROP INDEX IF EXISTS learning_hub.idx_lesson_feedback_course_rating;
DROP TABLE IF EXISTS learning_hub.lesson_feedback;

COMMIT;
