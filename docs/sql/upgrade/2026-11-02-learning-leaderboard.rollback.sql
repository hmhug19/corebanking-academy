-- Rollback of 2026-11-02-learning-leaderboard.sql. Drops the table and with it every recorded learning day,
-- so the streaks restart from zero. Points that come from progress and quiz passes are not affected.
-- Deploy a backend without the learning leaderboard first: it fails on startup once the table is gone.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-02-learning-leaderboard.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP INDEX IF EXISTS learning_hub.idx_learning_activity_days_user_date;
DROP TABLE IF EXISTS learning_hub.learning_activity_days;

COMMIT;
