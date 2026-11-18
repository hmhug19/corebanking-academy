-- Rolls back 2026-11-27-external-assistant-connections.sql. Roll the backend back first: the new one reads this table.
-- Every Kết nối trợ lý ngoài is lost; the external assistants using them get 401 from then on.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-27-external-assistant-connections.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP TABLE IF EXISTS learning_hub.external_assistant_connection;

COMMIT;
