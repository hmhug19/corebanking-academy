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

CREATE TABLE IF NOT EXISTS learning_hub.oauth_authorization_request (
    request_id varchar(64) PRIMARY KEY,
    client_id varchar(64) NOT NULL,
    redirect_uri varchar(500) NOT NULL,
    state varchar(500),
    code_challenge varchar(128) NOT NULL,
    scope varchar(100) NOT NULL,
    browser_hash char(64) NOT NULL,
    create_time timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);
COMMENT ON TABLE learning_hub.oauth_authorization_request IS
    'Yêu cầu cấp quyền đang chờ người dùng Cho phép hoặc Từ chối trên trang Cho phép kết nối; dùng một lần, hết hạn sau 10 phút.';
COMMENT ON COLUMN learning_hub.oauth_authorization_request.browser_hash IS
    'SHA-256 của cookie chan_oauth_request đặt lúc authorize; chỉ trình duyệt đó trả lời được yêu cầu.';

CREATE TABLE IF NOT EXISTS learning_hub.oauth_authorization_code (
    code_hash char(64) PRIMARY KEY,
    connection_id bigint NOT NULL,
    redirect_uri varchar(500) NOT NULL,
    code_challenge varchar(128) NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);
COMMENT ON TABLE learning_hub.oauth_authorization_code IS
    'Code một lần (60 giây) sau khi người dùng Cho phép; chỉ lưu SHA-256 của code.';

ALTER TABLE learning_hub.external_assistant_connection
    ADD COLUMN IF NOT EXISTS kind varchar(10) NOT NULL DEFAULT 'token',
    ADD COLUMN IF NOT EXISTS client_id varchar(64),
    ADD COLUMN IF NOT EXISTS access_token_hash char(64),
    ADD COLUMN IF NOT EXISTS access_expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS refresh_token_hash char(64),
    ADD COLUMN IF NOT EXISTS previous_refresh_hash char(64),
    ADD COLUMN IF NOT EXISTS refresh_rotated_at timestamptz,
    ALTER COLUMN token_hash DROP NOT NULL,
    ALTER COLUMN token_prefix DROP NOT NULL;
ALTER TABLE learning_hub.external_assistant_connection
    DROP CONSTRAINT IF EXISTS ck_external_assistant_connection_kind;
ALTER TABLE learning_hub.external_assistant_connection
    ADD CONSTRAINT ck_external_assistant_connection_kind CHECK (
        (kind = 'token' AND token_hash IS NOT NULL AND token_prefix IS NOT NULL)
        OR (kind = 'oauth' AND client_id IS NOT NULL));
CREATE UNIQUE INDEX IF NOT EXISTS uq_external_assistant_connection_access
    ON learning_hub.external_assistant_connection (access_token_hash);
CREATE UNIQUE INDEX IF NOT EXISTS uq_external_assistant_connection_refresh
    ON learning_hub.external_assistant_connection (refresh_token_hash);
CREATE INDEX IF NOT EXISTS idx_external_assistant_connection_previous_refresh
    ON learning_hub.external_assistant_connection (previous_refresh_hash);
COMMENT ON COLUMN learning_hub.external_assistant_connection.kind IS
    'token: mã chan_ext_ dán tay (ADR 0004); oauth: cấp qua trang Cho phép kết nối (ADR 0005).';
COMMENT ON COLUMN learning_hub.external_assistant_connection.client_id IS 'Client OAuth (oauth_client) của kết nối oauth.';
COMMENT ON COLUMN learning_hub.external_assistant_connection.access_token_hash IS
    'SHA-256 của access token hiện tại (chan_oat_, sống 1 giờ); token không bao giờ được lưu hay ghi log.';
COMMENT ON COLUMN learning_hub.external_assistant_connection.refresh_token_hash IS
    'SHA-256 của refresh token hiện tại (chan_ort_); đổi mới mỗi lần refresh.';
COMMENT ON COLUMN learning_hub.external_assistant_connection.previous_refresh_hash IS
    'SHA-256 của refresh token trước đó: dùng lại nó nghĩa là token bị lộ, kết nối bị thu hồi (trừ trong 30 giây sau khi xoay).';
COMMENT ON COLUMN learning_hub.external_assistant_connection.refresh_rotated_at IS
    'Lúc refresh token được xoay gần nhất; dùng lại token cũ ngay sau đó (hai phiên cùng refresh) chỉ bị từ chối.';
COMMENT ON COLUMN learning_hub.external_assistant_connection.expires_at IS
    'token: 30 hoặc 90 ngày từ lúc tạo; oauth: 30 ngày từ lần refresh gần nhất (hết hạn sau 30 ngày không dùng).';

COMMIT;
