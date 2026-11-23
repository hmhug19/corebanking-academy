-- Mô phỏng luồng: the flow block (block_type = 'flow') keeps its Đối tượng and Bước chuyển as one JSON document on the
-- block. NULL for every other block type. The backend validates the document; the DB has no constraint on it.
-- Run as dev_user (the backend's role). Safe to run again. Run it BEFORE deploying the backend.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-30-flow-model.sql
-- Rollback: docs/sql/upgrade/2026-11-30-flow-model.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.lesson_blocks ADD COLUMN IF NOT EXISTS flow_model jsonb;
COMMENT ON COLUMN learning_hub.lesson_blocks.flow_model IS 'Mô phỏng luồng của khối flow: JSON {"version": 1, "objects": [{id, name, kind, x, z}], "steps": [{id, from, to, title, description}]}, tối đa 30 đối tượng và 100 bước; NULL với loại khối khác. Service kiểm tra, không có ràng buộc DB.';

COMMIT;
