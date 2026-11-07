-- Rollback of 2026-11-07-leaderboard-opt-out.sql: drops the table; every hidden learner becomes visible again.
BEGIN;
SET LOCAL lock_timeout = '5s';
DROP TABLE IF EXISTS learning_hub.learner_preferences;
COMMIT;
