-- Rollback of 2026-11-07-levels.sql. Drops the level ladders and every learner's stored highest level.
-- Deploy a backend without levels first: it fails on startup once the tables are gone.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-levels.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP TABLE IF EXISTS learning_hub.learner_levels;
DROP TABLE IF EXISTS learning_hub.gamification_levels;

COMMIT;
