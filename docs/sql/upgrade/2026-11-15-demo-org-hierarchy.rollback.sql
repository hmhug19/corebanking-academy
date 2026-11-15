-- Rollback cho 2026-11-15-demo-org-hierarchy.sql, chỉ chạy SAU migration khi cần quay lại.
-- Bắt buộc dừng backend, worker và mọi tiến trình ghi; rà thủ công tham chiếu JSON/text,
-- schema khác, tích hợp và dữ liệu phát sinh sau migration. Snapshot không thay backup DB.
-- Nếu có ghi mới hoặc không chứng minh được an toàn, DỪNG và khôi phục backup theo quy trình.
-- psql -X -v ON_ERROR_STOP=1 -f <file>; dừng nếu có lỗi, không xóa snapshot thủ công.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5min';
SELECT pg_advisory_xact_lock(7142051963);
LOCK TABLE learning_hub.orgs IN ACCESS EXCLUSIVE MODE;

DO $rollback$
DECLARE
    v_backup learning_hub.demo_org_hierarchy_migration_backup%ROWTYPE;
    v_ban learning_hub.orgs%ROWTYPE;
    v_old_ban learning_hub.orgs%ROWTYPE;
    v_room learning_hub.orgs%ROWTYPE;
    v_ho learning_hub.orgs%ROWTYPE;
    v_count bigint;
    v_ref boolean;
    v_col record;
BEGIN
    SELECT count(*) INTO v_count FROM learning_hub.demo_org_hierarchy_migration_backup
    WHERE backup_key = '2026-11-15-demo-org-hierarchy';
    IF v_count <> 1 THEN RAISE EXCEPTION 'Không tìm thấy snapshot gốc duy nhất; không rollback tự động'; END IF;
    SELECT * INTO STRICT v_backup FROM learning_hub.demo_org_hierarchy_migration_backup
    WHERE backup_key = '2026-11-15-demo-org-hierarchy' FOR UPDATE;
    SELECT * INTO v_old_ban FROM jsonb_populate_record(NULL::learning_hub.orgs, v_backup.ban_before);
    SELECT * INTO v_ban FROM learning_hub.orgs WHERE org_id = v_old_ban.org_id;
    IF NOT FOUND OR v_ban.org_type <> 'DEPARTMENT' OR v_ban.parent_id IS DISTINCT FROM v_backup.ho_id
       OR (to_jsonb(v_ban) - ARRAY['parent_id','org_type','update_time'])
          IS DISTINCT FROM (v_backup.ban_before - ARRAY['parent_id','org_type','update_time']) THEN
        RAISE EXCEPTION 'Ban không ở trạng thái mong đợi hoặc đã bị sửa; không rollback tự động';
    END IF;
    SELECT * INTO v_ho FROM learning_hub.orgs WHERE org_id = v_backup.ho_id;
    IF NOT FOUND OR v_ho.org_type <> 'UNIT' OR v_ho.parent_id IS NOT NULL OR v_ho.status <> 'active'
       OR v_ho.code <> 'DEMO-HO-BIDV' OR v_ho.name <> '[DEMO] Trụ sở chính - BIDV' THEN
        RAISE EXCEPTION 'Trụ sở không ở trạng thái mong đợi; không rollback tự động';
    END IF;
    IF v_backup.ho_before IS NOT NULL AND to_jsonb(v_ho) IS DISTINCT FROM v_backup.ho_before THEN
        RAISE EXCEPTION 'Trụ sở có sẵn đã thay đổi; không rollback tự động';
    END IF;
    IF v_backup.room_before IS NOT NULL THEN
        SELECT * INTO v_room FROM jsonb_populate_record(NULL::learning_hub.orgs, v_backup.room_before);
        IF EXISTS (SELECT 1 FROM learning_hub.orgs WHERE org_id = v_room.org_id OR lower(code) = lower(v_room.code)) THEN
            RAISE EXCEPTION 'ID hoặc mã phòng cũ đã được dùng; không rollback tự động';
        END IF;
    END IF;
    -- Trụ sở được tạo mới chỉ được xóa nếu không có tham chiếu số nào khác ngoài Ban.
    IF v_backup.ho_before IS NULL THEN
        FOR v_col IN
            SELECT n.nspname AS schema_name, c.relname AS table_name, a.attname AS column_name
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            JOIN pg_attribute a ON a.attrelid = c.oid
            WHERE n.nspname = 'learning_hub' AND c.relkind IN ('r','p')
              AND a.attnum > 0 AND NOT a.attisdropped
              AND a.atttypid IN ('int2'::regtype,'int4'::regtype,'int8'::regtype,'numeric'::regtype)
              AND (a.attname ~* '(^|_)(org|organization|unit|department)(_|$)'
                   OR (c.relname = 'orgs' AND a.attname = 'parent_id'))
              AND NOT (c.relname = 'orgs' AND a.attname = 'org_id')
              AND c.relname <> 'demo_org_hierarchy_migration_backup'
        LOOP
            IF v_col.table_name = 'orgs' AND v_col.column_name = 'parent_id' THEN
                EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I WHERE %I = $1 AND org_id <> $2)',
                               v_col.schema_name, v_col.table_name, v_col.column_name)
                    INTO v_ref USING v_backup.ho_id, v_old_ban.org_id;
            ELSE
                EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I.%I WHERE %I = $1)',
                               v_col.schema_name, v_col.table_name, v_col.column_name)
                    INTO v_ref USING v_backup.ho_id;
            END IF;
            IF v_ref THEN RAISE EXCEPTION 'Trụ sở có tham chiếu tại %.%.%; không xóa',
                v_col.schema_name, v_col.table_name, v_col.column_name; END IF;
        END LOOP;
    END IF;
    UPDATE learning_hub.orgs SET parent_id = v_old_ban.parent_id, org_type = v_old_ban.org_type,
        update_time = v_old_ban.update_time WHERE org_id = v_old_ban.org_id;
    IF v_backup.room_before IS NOT NULL THEN
        INSERT INTO learning_hub.orgs SELECT (jsonb_populate_record(NULL::learning_hub.orgs, v_backup.room_before)).*;
    END IF;
    IF v_backup.ho_before IS NULL THEN DELETE FROM learning_hub.orgs WHERE org_id = v_backup.ho_id; END IF;
    DELETE FROM learning_hub.demo_org_hierarchy_migration_backup
    WHERE backup_key = '2026-11-15-demo-org-hierarchy';
    RAISE NOTICE 'Đã trả Ban ID % về cơ cấu cũ; phòng cũ được khôi phục nếu snapshot có', v_old_ban.org_id;
END
$rollback$;
SELECT org_id, parent_id, org_type, code, name, status FROM learning_hub.orgs
WHERE lower(code) IN (lower('DEMO-HO-BIDV'), lower('BIDV110B803'), lower('TEST-20261101'))
ORDER BY parent_id NULLS FIRST, org_id;
COMMIT;
