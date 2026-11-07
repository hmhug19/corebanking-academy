-- Levels (gamification group A, ticket 05): the level ladder of each config version and the highest level each learner
-- reached. Run once on a DB that already has 2026-11-07-xp-ledger.sql (a new DB gets the tables from learning_hub_learning.sql).
-- Single transaction: running it twice fails at CREATE TABLE and changes nothing. Config versions that already exist get the
-- default ladder. The backend refuses to start without these tables (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-07-levels.sql
-- Rollback: docs/sql/upgrade/2026-11-07-levels.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

CREATE TABLE learning_hub.gamification_levels (
    version_id bigint NOT NULL,
    level_no integer NOT NULL,
    name varchar(100) NOT NULL,
    min_xp integer NOT NULL,
    CONSTRAINT pk_gamification_levels PRIMARY KEY (version_id, level_no),
    CONSTRAINT ck_gamification_levels CHECK (level_no >= 1 AND min_xp >= 0)
);
COMMENT ON TABLE learning_hub.gamification_levels IS 'Bậc thang cấp độ của một phiên bản cấu hình; bất biến cùng phiên bản. Cấp 1 bắt đầu từ 0 XP.';
COMMENT ON COLUMN learning_hub.gamification_levels.version_id IS 'Phiên bản cấu hình (gamification_config_versions.version_id, ID logic không FK).';
COMMENT ON COLUMN learning_hub.gamification_levels.level_no IS 'Số cấp, từ 1.';
COMMENT ON COLUMN learning_hub.gamification_levels.name IS 'Tên cấp hiển thị cho học viên.';
COMMENT ON COLUMN learning_hub.gamification_levels.min_xp IS 'Tổng XP tối thiểu để đạt cấp này.';

CREATE TABLE learning_hub.learner_levels (
    user_id varchar(20) PRIMARY KEY,
    level_no integer NOT NULL,
    reached_at timestamptz NOT NULL DEFAULT now(),
    update_time timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_learner_levels CHECK (level_no >= 1)
);
COMMENT ON TABLE learning_hub.learner_levels IS 'Cấp cao nhất học viên từng đạt; chỉ tăng, không bao giờ giảm dù bậc thang được nâng ngưỡng.';
COMMENT ON COLUMN learning_hub.learner_levels.user_id IS 'Mã cán bộ học viên (ID logic không FK).';
COMMENT ON COLUMN learning_hub.learner_levels.level_no IS 'Cấp cao nhất đã đạt.';
COMMENT ON COLUMN learning_hub.learner_levels.reached_at IS 'Lúc đạt cấp hiện tại.';
COMMENT ON COLUMN learning_hub.learner_levels.update_time IS 'Lần cập nhật gần nhất.';

INSERT INTO learning_hub.gamification_levels (version_id, level_no, name, min_xp)
SELECT v.version_id, l.level_no, l.name, l.min_xp
FROM learning_hub.gamification_config_versions v
CROSS JOIN (VALUES (1, 'Giao dịch viên tập sự', 0), (2, 'Giao dịch viên', 200), (3, 'Chuyên viên', 600),
                   (4, 'Chuyên viên chính', 1500), (5, 'Chuyên gia nghiệp vụ', 3500),
                   (6, 'Chuyên gia Core Banking', 7000)) AS l(level_no, name, min_xp)
WHERE NOT EXISTS (SELECT 1 FROM learning_hub.gamification_levels x WHERE x.version_id = v.version_id);

COMMIT;
