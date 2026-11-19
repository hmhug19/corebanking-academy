-- OAuth for the Trợ lý ngoài (ADR 0005 in lms-vip-pro-max): clients that registered themselves, pending
-- authorization requests, one-time codes, and the OAuth columns of external_assistant_connection.
-- Needs 2026-11-27-external-assistant-connections.sql. Run before the new backend; keeps dev_user's access.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-30-external-assistant-oauth.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS learning_hub.oauth_client (
    client_id varchar(64) PRIMARY KEY,
    client_name varchar(100) NOT NULL,
    redirect_uris jsonb NOT NULL,
    create_time timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE learning_hub.oauth_client IS
    'Client OAuth tự đăng ký (RFC 7591) để kết nối Trợ lý ngoài; client public, không có secret.';
COMMENT ON COLUMN learning_hub.oauth_client.client_name IS 'Tên do client tự khai; CHAN Academy không xác minh.';
COMMENT ON COLUMN learning_hub.oauth_client.redirect_uris IS
    'Mảng JSON các redirect URI đã đăng ký: callback của Claude hoặc loopback (localhost, 127.0.0.1).';

COMMIT;
