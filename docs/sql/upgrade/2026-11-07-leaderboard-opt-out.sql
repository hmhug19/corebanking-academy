-- Leaderboard opt-out (gamification group A, ticket 08): a per-learner preference to hide the name from other learners'
-- leaderboards. Run once on a DB that already has 2026-11-07-badges.sql (a new DB gets the table from
-- learning_hub_learning.sql). Single transaction: running it twice fails at CREATE TABLE and changes nothing. Everyone is
-- visible until they choose otherwise; no row is needed for a visible learner.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-leaderboard-opt-out.sql
-- Rollback: docs/sql/upgrade/2026-11-07-leaderboard-opt-out.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TABLE learning_hub.learner_preferences (
    user_id varchar(20) NOT NULL,
    leaderboard_hidden boolean NOT NULL DEFAULT false,
    update_time timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_learner_preferences PRIMARY KEY (user_id)
);
COMMENT ON TABLE learning_hub.learner_preferences IS 'Tùy chọn của học viên về gamification; chỉ có dòng khi học viên đã đổi một tùy chọn khỏi mặc định. Hiện chỉ có việc ẩn tên khỏi bảng xếp hạng.';
COMMENT ON COLUMN learning_hub.learner_preferences.user_id IS 'Mã cán bộ học viên (ID logic không FK).';
COMMENT ON COLUMN learning_hub.learner_preferences.leaderboard_hidden IS 'true: ẩn tên khỏi bảng xếp hạng của người khác, hạng chung khép lại; người đó vẫn thấy hạng của mình và XP vẫn tính vào đơn vị. Mặc định false (hiện).';
COMMENT ON COLUMN learning_hub.learner_preferences.update_time IS 'Lần đổi gần nhất.';

COMMIT;
