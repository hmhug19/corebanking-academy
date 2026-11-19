-- Rolls back 2026-11-30-external-assistant-oauth.sql. Roll the backend back first: the new one reads these tables.
-- Every OAuth client and every oauth Kết nối trợ lý ngoài is lost; chan_ext_ connections stay. Trợ lý ngoài
-- clients connected by OAuth get 401 and have to connect again with a chan_ext_ token.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-30-external-assistant-oauth.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DELETE FROM learning_hub.external_assistant_connection WHERE kind = 'oauth';
ALTER TABLE learning_hub.external_assistant_connection
    DROP CONSTRAINT IF EXISTS ck_external_assistant_connection_kind;
DROP INDEX IF EXISTS learning_hub.uq_external_assistant_connection_access;
DROP INDEX IF EXISTS learning_hub.uq_external_assistant_connection_refresh;
DROP INDEX IF EXISTS learning_hub.idx_external_assistant_connection_previous_refresh;
ALTER TABLE learning_hub.external_assistant_connection
    DROP COLUMN IF EXISTS kind,
    DROP COLUMN IF EXISTS client_id,
    DROP COLUMN IF EXISTS access_token_hash,
    DROP COLUMN IF EXISTS access_expires_at,
    DROP COLUMN IF EXISTS refresh_token_hash,
    DROP COLUMN IF EXISTS previous_refresh_hash,
    DROP COLUMN IF EXISTS refresh_rotated_at,
    ALTER COLUMN token_hash SET NOT NULL,
    ALTER COLUMN token_prefix SET NOT NULL;
DROP TABLE IF EXISTS learning_hub.oauth_authorization_code;
DROP TABLE IF EXISTS learning_hub.oauth_authorization_request;
DROP TABLE IF EXISTS learning_hub.oauth_client;

COMMIT;
