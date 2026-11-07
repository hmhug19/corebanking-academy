-- Gamification notices (group A, ticket 11): "badge earned" and "level up" join the learner reminders feed as two new kinds
-- of learning_reminders row. Run once on a DB that already has 2026-11-07-leaderboard-opt-out.sql (a new DB gets the columns
-- from learning_hub_learning.sql). Single transaction. Existing rows become kind DEADLINE and are unchanged.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-gamification-notices.sql
-- Rollback: docs/sql/upgrade/2026-11-07-gamification-notices.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.learning_reminders ALTER COLUMN recipient_id DROP NOT NULL;
ALTER TABLE learning_hub.learning_reminders ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'DEADLINE';
ALTER TABLE learning_hub.learning_reminders ADD COLUMN user_id varchar(20);
ALTER TABLE learning_hub.learning_reminders ADD COLUMN subject varchar(200);
ALTER TABLE learning_hub.learning_reminders ADD CONSTRAINT ck_learning_reminders_kind
    CHECK (kind IN ('DEADLINE', 'BADGE_EARNED', 'LEVEL_UP'));
CREATE INDEX idx_learning_reminders_notice ON learning_hub.learning_reminders (user_id) WHERE kind <> 'DEADLINE';
COMMENT ON COLUMN learning_hub.learning_reminders.kind IS 'DEADLINE: nhắc hạn của một nghĩa vụ giao học (recipient_id). BADGE_EARNED, LEVEL_UP: thông báo gamification của user_id, subject là tên huy hiệu hoặc tên cấp; chỉ tạo khi đạt do sự kiện, trao bù huy hiệu không tạo.';
COMMENT ON COLUMN learning_hub.learning_reminders.user_id IS 'Người nhận thông báo gamification (null với DEADLINE, người nhận lấy từ recipient_id).';
COMMENT ON COLUMN learning_hub.learning_reminders.subject IS 'Tên huy hiệu hoặc tên cấp của thông báo gamification.';

COMMIT;
