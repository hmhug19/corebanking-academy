-- Assistant pending actions: the data changes the quick assistant proposed (a card the user confirms or cancels),
-- kept in the database instead of the server's memory so they survive a restart, work on several instances and can be
-- shown with their real outcome when a conversation is reopened.
-- Run once on a DB that already has the Identity baseline and 2026-11-10-assistant-conversations.sql.
-- Safe to run again: it only adds what is missing. No data is changed.
-- The backend refuses to start without this table (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-10-assistant-pending-actions.sql
-- Rollback: docs/sql/upgrade/2026-11-10-assistant-pending-actions.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS learning_hub.assistant_pending_actions (
    action_id varchar(40) PRIMARY KEY,
    owner_id varchar(20) NOT NULL,
    tool varchar(60) NOT NULL,
    arguments jsonb NOT NULL,
    title varchar(255) NOT NULL,
    changes jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    result_message varchar(500),
    expires_at timestamptz NOT NULL,
    resolved_time timestamptz,
    create_time timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_assistant_pending_actions_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'FAILED'))
);
COMMENT ON TABLE learning_hub.assistant_pending_actions IS
    'Thay đổi dữ liệu do trợ lý AI đề xuất và chờ người dùng xác nhận. Chỉ chủ sở hữu xác nhận hoặc huỷ được, mỗi đề xuất một lần. Quan hệ bằng cột ID thông thường, không FK.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.action_id IS 'Mã đề xuất (UUID dạng chuỗi), không đoán được.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.owner_id IS 'Người dùng đã gây ra đề xuất (users.user_id, ID logic không FK); chỉ người này xác nhận hoặc huỷ được.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.tool IS 'Công cụ sẽ chạy khi xác nhận, ví dụ update_profile.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.arguments IS 'Giá trị đã kiểm tra mà công cụ sẽ áp dụng khi xác nhận.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.title IS 'Tiêu đề thẻ xác nhận người dùng thấy.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.changes IS 'Các thay đổi đã hiện cho người dùng (mảng JSON: field, label, from, to), đọc từ máy chủ lúc đề xuất.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.status IS 'PENDING (chờ) | CONFIRMED (đã xác nhận) | CANCELLED (đã huỷ) | FAILED (đã xác nhận nhưng công cụ từ chối). Hết hạn là PENDING có expires_at đã qua, không lưu thành trạng thái riêng.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.result_message IS 'Kết quả hiện cho người dùng sau khi xác nhận hoặc lý do bị từ chối; NULL khi còn chờ.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.expires_at IS 'Hết hạn sau app.assistant.agent.action-ttl kể từ lúc đề xuất.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.resolved_time IS 'Lúc xác nhận hoặc huỷ; NULL khi còn chờ.';
COMMENT ON COLUMN learning_hub.assistant_pending_actions.create_time IS 'DB sinh thời điểm tạo.';

CREATE INDEX IF NOT EXISTS idx_assistant_pending_actions_owner
    ON learning_hub.assistant_pending_actions(owner_id, status, create_time);
CREATE INDEX IF NOT EXISTS idx_assistant_pending_actions_created
    ON learning_hub.assistant_pending_actions(create_time);

COMMIT;
