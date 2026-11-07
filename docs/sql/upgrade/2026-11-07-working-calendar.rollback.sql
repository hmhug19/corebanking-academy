-- Rollback of 2026-11-07-working-calendar.sql. Drops the calendar; the streak endpoint then fails until redeployed without it.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-working-calendar.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP TABLE IF EXISTS learning_hub.working_calendar_days;

COMMIT;
