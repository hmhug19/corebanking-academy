-- Badge backfill progress (gamification group A, ticket 13): the admin creates a badge and the backfill runs in the
-- background, so the definition now records how far it got. Run once on a DB that already has 2026-11-07-badges.sql
-- (a new DB gets the columns from learning_hub_learning.sql). Single transaction: running it twice fails at ADD COLUMN
-- and changes nothing. Existing badges (the starter set) are marked DONE. The backend reads these columns with native SQL,
-- so run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-badge-backfill-progress.sql
-- Rollback: docs/sql/upgrade/2026-11-07-badge-backfill-progress.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.badge_definitions
    ADD COLUMN backfill_status varchar(20) NOT NULL DEFAULT 'DONE',
    ADD COLUMN backfill_total integer NOT NULL DEFAULT 0,
    ADD COLUMN backfill_done integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_badge_definitions_backfill CHECK (backfill_status IN ('RUNNING', 'DONE', 'FAILED')
        AND backfill_total >= 0 AND backfill_done >= 0);
COMMENT ON COLUMN learning_hub.badge_definitions.backfill_status IS 'Trao bù nền khi tạo huy hiệu hoặc đổi luật: RUNNING (đang chạy), DONE, FAILED. Chạy lại an toàn vì mỗi (người, huy hiệu) một dòng.';
COMMENT ON COLUMN learning_hub.badge_definitions.backfill_total IS 'Số người đủ điều kiện tìm thấy lúc bắt đầu trao bù.';
COMMENT ON COLUMN learning_hub.badge_definitions.backfill_done IS 'Số người đã xử lý (đã được trao hoặc đã có huy hiệu) trong lần trao bù gần nhất.';

COMMIT;
