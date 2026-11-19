-- Rolls back 2026-11-30-external-assistant-oauth.sql. Roll the backend back first: the new one reads these tables.
-- Every OAuth client is lost; Trợ lý ngoài clients have to register and connect again.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-30-external-assistant-oauth.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP TABLE IF EXISTS learning_hub.oauth_client;

COMMIT;
