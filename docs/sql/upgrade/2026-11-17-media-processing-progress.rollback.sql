-- Rolls back 2026-11-17-media-processing-progress.sql. Roll the backend back first: the new one maps this column.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-17-media-processing-progress.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.assets DROP COLUMN IF EXISTS processing_progress;

COMMIT;
