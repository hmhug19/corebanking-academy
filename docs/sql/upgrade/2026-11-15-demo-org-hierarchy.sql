-- Chuẩn hóa cơ cấu demo: Trụ sở chính (UNIT) -> Ban Core Banking (DEPARTMENT).
-- PostgreSQL 14+. Chỉ chạy bằng dev_user sau backup/restore thử và diễn tập trên bản sao.
-- DỪNG tất cả backend, worker và các tiến trình ghi trong suốt preflight, migration,
-- kiểm tra và quyết định rollback. Không chạy trên môi trường đang phục vụ người dùng.
-- Giữ org_id, code, tên, trạng thái, nhóm, grant và dữ liệu Learning của Ban.
-- Phòng kiểm thử chỉ xóa khi đã rà soát và các kiểm tra bên dưới không phát hiện tham chiếu.
-- Mã DEMO-HO-BIDV là mã giả lập: xác nhận/đổi trước khi chạy, không suy ra mã BIDV thật.
-- Không sử dụng script này để khởi tạo database mới hoặc thay thế bản backup.
-- Chạy với psql -X -v ON_ERROR_STOP=1 -f <file>; không nối sang script khác khi có lỗi.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5min';
SELECT pg_advisory_xact_lock(7142051963);
LOCK TABLE learning_hub.orgs IN ACCESS EXCLUSIVE MODE;

-- Trạng thái phục hồi nằm cùng transaction, không thay thế bản sao lưu database.
CREATE TABLE IF NOT EXISTS learning_hub.demo_org_hierarchy_migration_backup (
    backup_key text PRIMARY KEY,
    ban_before jsonb NOT NULL,
    room_before jsonb,
    ho_before jsonb,
    ho_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

DO $migration$
DECLARE
    v_ban learning_hub.orgs%ROWTYPE;
    v_ho learning_hub.orgs%ROWTYPE;
    v_room learning_hub.orgs%ROWTYPE;
    v_count bigint;
    v_room_count integer;
    v_has_refs boolean;
    v_col record;
    v_ho_code constant text := 'DEMO-HO-BIDV';
    v_room_code constant text := 'TEST-20261101';
BEGIN
    IF EXISTS (SELECT 1 FROM learning_hub.demo_org_hierarchy_migration_backup
               WHERE backup_key = '2026-11-15-demo-org-hierarchy') THEN
        RAISE EXCEPTION 'Đã có snapshot migration; không ghi đè. Rà soát trước khi chạy lại';
    END IF;
    SELECT count(*) INTO v_count FROM learning_hub.orgs WHERE lower(code) = lower('BIDV110B803');
    IF v_count <> 1 THEN
        RAISE EXCEPTION 'Cần đúng một Ban mã BIDV110B803; tìm thấy %', v_count;
    END IF;
    SELECT * INTO STRICT v_ban FROM learning_hub.orgs WHERE lower(code) = lower('BIDV110B803');
    IF v_ban.status <> 'active' THEN
        RAISE EXCEPTION 'Ban không active (ID %)', v_ban.org_id;
    END IF;

    SELECT count(*) INTO v_count FROM learning_hub.orgs WHERE lower(code) = lower(v_ho_code);
    IF v_count > 1 THEN RAISE EXCEPTION 'Mã Trụ sở chính bị trùng'; END IF;
    IF v_count = 1 THEN
        SELECT * INTO STRICT v_ho FROM learning_hub.orgs WHERE lower(code) = lower(v_ho_code);
        IF v_ho.org_type <> 'UNIT' OR v_ho.parent_id IS NOT NULL OR v_ho.status <> 'active'
           OR v_ho.name <> '[DEMO] Trụ sở chính - BIDV' THEN
            RAISE EXCEPTION 'Mã Trụ sở chính đã tồn tại nhưng không đúng cấu hình; kiểm tra thủ công';
        END IF;
    END IF;
    IF v_ban.org_type <> 'UNIT' OR v_ban.parent_id IS NOT NULL THEN
        RAISE EXCEPTION 'Cấu trúc Ban không phải trạng thái trước migration; kiểm tra thủ công';
    END IF;

    SELECT count(*) INTO v_room_count FROM learning_hub.orgs WHERE lower(code) = lower(v_room_code);
    IF v_room_count > 1 THEN RAISE EXCEPTION 'Mã phòng kiểm thử bị trùng'; END IF;
    IF v_room_count = 1 THEN
        SELECT * INTO STRICT v_room FROM learning_hub.orgs WHERE lower(code) = lower(v_room_code);
        IF v_room.parent_id IS DISTINCT FROM v_ban.org_id OR v_room.org_type <> 'DEPARTMENT'
           OR v_room.name <> 'TEST-20261101 Phòng kiểm thử' THEN
            RAISE EXCEPTION 'Node mã % không khớp phòng kiểm thử trực thuộc Ban; không xóa', v_room_code;
        END IF;
        -- Quét thận trọng các cột số có tên biểu thị ID tổ chức trong mọi bảng thường/partition
        -- của schema. Các cột JSON/text, schema khác và tham chiếu không theo tên phải rà thủ công.
        FOR v_col IN
            SELECT n.nspname AS schema_name, c.relname AS table_name, a.attname AS column_name
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE n.nspname = 'learning_hub' AND c.relkind IN ('r', 'p')
              AND a.attnum > 0 AND NOT a.attisdropped
              AND a.atttypid IN ('int2'::regtype, 'int4'::regtype, 'int8'::regtype, 'numeric'::regtype)
              AND (a.attname ~* '(^|_)(org|organization|unit|department)(_|$)'
                   OR (c.relname = 'orgs' AND a.attname = 'parent_id'))
              AND NOT (c.relname = 'orgs' AND a.attname = 'org_id')
        LOOP
            EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I WHERE %I = $1)',
                           v_col.schema_name, v_col.table_name, v_col.column_name)
                INTO v_has_refs USING v_room.org_id;
            IF v_has_refs THEN
                RAISE EXCEPTION 'Không xóa phòng ID %: có tham chiếu tại %.%.%',
                    v_room.org_id, v_col.schema_name, v_col.table_name, v_col.column_name;
            END IF;
        END LOOP;
        RAISE NOTICE 'Đã qua kiểm tra cột số cho phòng kiểm thử ID %', v_room.org_id;
    END IF;

    -- Bất kỳ tổ chức con nào khác cũng phải được giải quyết riêng trước khi Ban thành phòng.
    IF EXISTS (SELECT 1 FROM learning_hub.orgs WHERE parent_id = v_ban.org_id
               AND (v_room_count = 0 OR org_id <> v_room.org_id)) THEN
        RAISE EXCEPTION 'Ban còn node tổ chức con khác; dừng để rà soát';
    END IF;

    IF v_count = 0 THEN
        INSERT INTO learning_hub.orgs(parent_id, org_type, code, name, status, sort_no)
        VALUES (NULL, 'UNIT', v_ho_code, '[DEMO] Trụ sở chính - BIDV', 'active', 0)
        RETURNING * INTO v_ho;
    END IF;
    INSERT INTO learning_hub.demo_org_hierarchy_migration_backup
        (backup_key, ban_before, room_before, ho_before, ho_id)
    VALUES ('2026-11-15-demo-org-hierarchy', to_jsonb(v_ban),
            CASE WHEN v_room_count = 1 THEN to_jsonb(v_room) ELSE NULL END,
            CASE WHEN v_count = 1 THEN to_jsonb(v_ho) ELSE NULL END, v_ho.org_id);
    IF v_room_count = 1 THEN
        DELETE FROM learning_hub.orgs WHERE org_id = v_room.org_id;
        RAISE NOTICE 'Đã xóa node phòng kiểm thử ID %', v_room.org_id;
    END IF;
    UPDATE learning_hub.orgs SET parent_id = v_ho.org_id, org_type = 'DEPARTMENT', update_time = now()
    WHERE org_id = v_ban.org_id AND (parent_id IS DISTINCT FROM v_ho.org_id OR org_type <> 'DEPARTMENT');
    RAISE NOTICE 'Trụ sở ID %, Ban ID % giữ nguyên; nhóm vẫn thuộc Ban', v_ho.org_id, v_ban.org_id;
END
$migration$;

-- Kiểm tra trước COMMIT: mọi nhóm gắn Ban vẫn giữ nguyên org_id; không sửa dữ liệu khóa học.
SELECT org_id, parent_id, org_type, code, name, status FROM learning_hub.orgs
WHERE lower(code) IN (lower('DEMO-HO-BIDV'), lower('BIDV110B803'), lower('TEST-20261101'))
ORDER BY parent_id NULLS FIRST, org_id;
SELECT count(*) AS teams_under_ban FROM learning_hub.teams
WHERE org_id = (SELECT org_id FROM learning_hub.orgs WHERE lower(code) = lower('BIDV110B803'));
COMMIT;
