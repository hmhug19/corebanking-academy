-- Learner quiz: a lesson of type 'quiz' points to one quiz of the same course.
-- Run once on a DB that already has the Learning baseline (a new DB gets the column from learning_hub_learning.sql).
-- Safe to run again: it only adds what is missing. No data is changed.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-01-learner-quiz.sql
-- Rollback: docs/sql/upgrade/2026-11-01-learner-quiz.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_lessons ADD COLUMN IF NOT EXISTS quiz_id bigint;
COMMENT ON COLUMN learning_hub.course_lessons.quiz_id IS
    'Bài kiểm tra của bài học loại quiz (quizzes.quiz_id, cùng course, ID logic không FK); NULL với bài nội dung.';
CREATE INDEX IF NOT EXISTS idx_course_lessons_quiz ON learning_hub.course_lessons (quiz_id) WHERE quiz_id IS NOT NULL;

COMMIT;
