-- Assistant feedback: the 👍/👎 a user gives to one answer of the AI assistants, with an optional reason and note.
-- Run once on a DB that already has 2026-11-10-assistant-conversations.sql. Safe to run again: it only adds what is missing.
-- The backend refuses to start without this table (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-10-assistant-feedback.sql
-- Rollback: docs/sql/upgrade/2026-11-10-assistant-feedback.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS learning_hub.assistant_feedback (
    turn_id bigint PRIMARY KEY,
    owner_id varchar(20) NOT NULL,
    helpful boolean NOT NULL,
    reason_code varchar(20),
    note varchar(2000),
    create_time timestamptz NOT NULL DEFAULT now(),
    update_time timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_assistant_feedback_reason CHECK (
        reason_code IS NULL OR reason_code IN ('irrelevant', 'incorrect', 'outdated', 'missing_source', 'other')),
    CONSTRAINT ck_assistant_feedback_reason_needs_thumbs_down CHECK (helpful = false OR reason_code IS NULL)
);
COMMENT ON TABLE learning_hub.assistant_feedback IS
    'Đánh giá 👍/👎 của người dùng cho một câu trả lời của trợ lý AI; mỗi lượt một đánh giá, sửa được. Quan hệ bằng cột ID thông thường, không FK.';
COMMENT ON COLUMN learning_hub.assistant_feedback.turn_id IS 'Lượt được đánh giá (assistant_turns.turn_id, ID logic không FK); cũng là khoá chính vì một lượt chỉ có một chủ. Xoá hội thoại thì service xoá đánh giá.';
COMMENT ON COLUMN learning_hub.assistant_feedback.owner_id IS 'Người đánh giá, cũng là chủ hội thoại (users.user_id, ID logic không FK).';
COMMENT ON COLUMN learning_hub.assistant_feedback.helpful IS 'TRUE = hữu ích (👍), FALSE = chưa tốt (👎).';
COMMENT ON COLUMN learning_hub.assistant_feedback.reason_code IS 'Lý do khi 👎: irrelevant | incorrect | outdated | missing_source | other; NULL khi 👍 hoặc không chọn.';
COMMENT ON COLUMN learning_hub.assistant_feedback.note IS 'Ghi chú tuỳ chọn của người dùng, tối đa 2000 ký tự. Log không bao giờ chứa văn bản này.';
COMMENT ON COLUMN learning_hub.assistant_feedback.create_time IS 'DB sinh thời điểm tạo.';
COMMENT ON COLUMN learning_hub.assistant_feedback.update_time IS 'JPA cập nhật thời điểm thay đổi.';

CREATE INDEX IF NOT EXISTS idx_assistant_feedback_owner
    ON learning_hub.assistant_feedback(owner_id);

COMMIT;
