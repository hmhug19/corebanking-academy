-- Rollback of 2026-11-07-badge-backfill-progress.sql: drops the three backfill columns; progress of past backfills is lost.
BEGIN;
SET LOCAL lock_timeout = '5s';
ALTER TABLE learning_hub.badge_definitions
    DROP CONSTRAINT IF EXISTS ck_badge_definitions_backfill,
    DROP COLUMN IF EXISTS backfill_done,
    DROP COLUMN IF EXISTS backfill_total,
    DROP COLUMN IF EXISTS backfill_status;
COMMIT;
