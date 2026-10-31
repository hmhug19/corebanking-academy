-- Rollback of 2026-11-01-learner-quiz.sql. Stops if any lesson of type 'quiz' exists, so no quiz link is lost.
-- Remove or convert those lessons through the course editor first.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-01-learner-quiz.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DO $rollback$
DECLARE
    v_count bigint;
BEGIN
    SELECT count(*) INTO v_count FROM learning_hub.course_lessons WHERE lesson_type = 'quiz';
    IF v_count > 0 THEN
        RAISE EXCEPTION 'Còn % bài học loại quiz: xóa hoặc đổi sang bài nội dung trước khi rollback.', v_count;
    END IF;
END;
$rollback$;

DROP INDEX IF EXISTS learning_hub.idx_course_lessons_quiz;
ALTER TABLE learning_hub.course_lessons DROP COLUMN IF EXISTS quiz_id;

COMMIT;
