-- Rollback of 2026-11-30-flow-model.sql. Run only after rolling the backend back: it drops every Mô phỏng luồng
-- document, and flow blocks left behind can no longer be read. Delete or convert those blocks first.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-30-flow-model.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.lesson_blocks DROP COLUMN IF EXISTS flow_model;

COMMIT;
