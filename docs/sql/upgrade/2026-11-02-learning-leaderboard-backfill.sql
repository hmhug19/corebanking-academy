-- Optional backfill for the learning leaderboard: one day per user for every local date (Asia/Ho_Chi_Minh) on which
-- the user touched a lesson (lesson_progress.update_time) or had a graded quiz attempt (quiz_attempts.submitted_at).
-- Run after 2026-11-02-learning-leaderboard.sql and the Learning and Assessment baselines.
-- Safe to run again: it skips the days a user already has, so the row count does not change on a second run.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-02-learning-leaderboard-backfill.sql
-- Undo: DELETE FROM learning_hub.learning_activity_days WHERE first_source = 'backfill';
BEGIN;
SET LOCAL lock_timeout = '5s';

INSERT INTO learning_hub.learning_activity_days (user_id, activity_date, first_source)
SELECT found.user_id, found.activity_date, 'backfill'
FROM (
    SELECT e.user_id, (lp.update_time AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS activity_date
    FROM learning_hub.lesson_progress lp
    JOIN learning_hub.course_enrollments e ON e.enrollment_id = lp.enrollment_id
    UNION
    SELECT a.user_id, (a.submitted_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date
    FROM learning_hub.quiz_attempts a
    WHERE a.status IN ('SUBMITTED', 'AUTO_SUBMITTED') AND a.submitted_at IS NOT NULL
) found
WHERE NOT EXISTS (
    SELECT 1 FROM learning_hub.learning_activity_days d
    WHERE d.user_id = found.user_id AND d.activity_date = found.activity_date
);

COMMIT;
