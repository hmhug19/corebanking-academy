-- Rollback of 2026-11-07-xp-ledger.sql. Drops the ledger and the config versions and with them every recorded XP award.
-- Deploy a backend without the XP ledger first: it fails on startup once the tables are gone.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-xp-ledger.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP TRIGGER IF EXISTS trg_xp_ledger_append_only ON learning_hub.xp_ledger;
DROP FUNCTION IF EXISTS learning_hub.xp_ledger_append_only();
DROP TABLE IF EXISTS learning_hub.xp_ledger;
DROP TABLE IF EXISTS learning_hub.gamification_config_versions;

COMMIT;
