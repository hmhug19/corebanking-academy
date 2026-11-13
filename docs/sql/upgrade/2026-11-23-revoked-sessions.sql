-- Logout ends only the session that asked for it. Each token now carries a session id (claim `sid`); logging out
-- records that id here until the token would have expired anyway, and the backend refuses a token whose session is
-- listed. Role, password and status changes still end every session of the account through users.auth_ver.
-- Tokens issued before this change have no `sid`: they stay valid, and logging out with one still ends every session.
-- A SCORM launch remembers the session that opened it (scorm_registrations.launch_session_id), so the player of a
-- logged-out session stops too; the column is added only where the SCORM tables exist.
-- Run before the new backend (it reads this table on every request with a `sid`). Safe to run again.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-23-revoked-sessions.sql
-- Rollback: docs/sql/upgrade/2026-11-23-revoked-sessions.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS learning_hub.revoked_sessions (
    session_id  varchar(36)  PRIMARY KEY,
    user_id     varchar(20)  NOT NULL,
    revoked_at  timestamptz  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  timestamptz  NOT NULL
);

CREATE INDEX IF NOT EXISTS revoked_sessions_expires_at_idx ON learning_hub.revoked_sessions (expires_at);

COMMENT ON TABLE learning_hub.revoked_sessions IS 'Phiên đã đăng xuất; giữ đến khi token của phiên hết hạn.';
COMMENT ON COLUMN learning_hub.revoked_sessions.session_id IS 'Claim sid của token (UUID).';
COMMENT ON COLUMN learning_hub.revoked_sessions.user_id IS 'Mã cán bộ của phiên, để tra cứu.';
COMMENT ON COLUMN learning_hub.revoked_sessions.expires_at IS 'Lúc token của phiên hết hạn; dòng quá hạn được xóa.';

DO $$
BEGIN
    IF to_regclass('learning_hub.scorm_registrations') IS NOT NULL THEN
        ALTER TABLE learning_hub.scorm_registrations ADD COLUMN IF NOT EXISTS launch_session_id varchar(36);
        COMMENT ON COLUMN learning_hub.scorm_registrations.launch_session_id
            IS 'Phiên (claim sid) đã mở lượt chạy; phiên đăng xuất thì lượt chạy dừng.';
    END IF;
END $$;

COMMIT;
