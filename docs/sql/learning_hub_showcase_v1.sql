-- Bộ dữ liệu tổng hợp DEMO riêng, phiên bản demo-showcase-v1.
-- PostgreSQL 14+ và extension vector(1024); cần đủ baseline hiện hành của 105 bảng.
-- Không chạy các baseline CREATE TABLE hoặc demo cũ trên DB có dữ liệu để chuẩn bị file này.
-- Chỉ INSERT vào namespace DEMO; UPDATE chỉ con trỏ các dòng vừa INSERT. Không đổi sequence/DDL thật.
-- Tài khoản SSO bị khóa, role/function inactive; không cấp quyền quản trị, không tạo mật khẩu.
-- Ngày tương đối tính tại lần chạy đầu. Chạy lại là NO-OP, không làm mới/ghi đè DEMO đã sửa.
-- Media chưa có bytes thật: failed/nháp. Không giả tệp READY, không xếp việc gửi tin hoặc gọi AI.
-- Đoạn Knowledge có vector zero chỉ thuộc tài liệu failed; không dùng làm embedding truy hồi thật.
-- Chưa được kiểm chứng bằng PostgreSQL trong môi trường tác giả: bắt buộc kiểm thử trên bản sao DB trước prod.
-- Chạy TOÀN BỘ file trong cùng một session: psql -X -v ON_ERROR_STOP=1 -d <database> -f <file.sql>
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '180s';
SET LOCAL TIME ZONE 'UTC';
SET LOCAL standard_conforming_strings = on;
SELECT pg_advisory_xact_lock(7142051964);

-- Hàm/tables chỉ tồn tại trong session hiện tại; không tạo đối tượng schema ứng dụng.
CREATE TEMP TABLE showcase_inserted (
    seq bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    table_name text NOT NULL,
    key_json jsonb NOT NULL
) ON COMMIT DROP;

CREATE OR REPLACE FUNCTION pg_temp.showcase_where(p_table text, p_alias text)
RETURNS text LANGUAGE sql STABLE AS $fn$
    SELECT string_agg(format('%I.%I IS NOT DISTINCT FROM k.%I', p_alias, a.attname, a.attname), ' AND '
                      ORDER BY a.attnum)
    FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
    WHERE i.indrelid = to_regclass('learning_hub.' || p_table) AND i.indisprimary;
$fn$;

CREATE OR REPLACE FUNCTION pg_temp.showcase_put(p_table text, p_data jsonb)
RETURNS jsonb LANGUAGE plpgsql AS $fn$
DECLARE
    v_columns text;
    v_values text;
    v_row jsonb;
    v_key jsonb;
BEGIN
    IF NOT (p_table = ANY(ARRAY['asset_upload_parts', 'assets', 'assignment_recipients', 'assignment_targets', 'attempt_answers', 'attempt_competency_scores', 'attempt_questions', 'attempt_regrades', 'audit_log', 'block_assets', 'block_progress', 'business_modules', 'community_ai_feedback', 'community_ai_turn_sources', 'community_ai_turns', 'community_answers', 'community_asset_upload_parts', 'community_assets', 'community_badge_awards', 'community_badges', 'community_collection_access_grants', 'community_collection_items', 'community_collections', 'community_comments', 'community_content_access_grants', 'community_content_guilds', 'community_content_links', 'community_content_revisions', 'community_content_tags', 'community_content_views', 'community_contents', 'community_contribution_events', 'community_guild_members', 'community_guilds', 'community_help_requests', 'community_knowledge_requests', 'community_learning_links', 'community_maintenance_reviews', 'community_media_transcripts', 'community_mentions', 'community_moderation_actions', 'community_moderators', 'community_notification_digest_batches', 'community_notification_digest_items', 'community_notifications', 'community_outbox_events', 'community_question_links', 'community_questions', 'community_radar_entries', 'community_reports', 'community_review_invitations', 'community_review_proposals', 'community_review_threads', 'community_revision_approvals', 'community_revision_assets', 'community_search_chunks', 'community_subscriptions', 'community_tag_aliases', 'community_tags', 'community_user_expertise', 'community_user_preferences', 'community_votes', 'community_wiki_pages', 'competencies', 'course_assessments', 'course_assignments', 'course_chapters', 'course_completion_rules', 'course_enrollments', 'course_lessons', 'course_objectives', 'course_revisions', 'course_shares', 'courses', 'functions', 'knowledge_document_chunks', 'knowledge_documents', 'learning_activity_days', 'learning_group_members', 'learning_groups', 'learning_reminders', 'learning_user_leases', 'lesson_blocks', 'lesson_bookmarks', 'lesson_feedback', 'lesson_notes', 'lesson_progress', 'orgs', 'process_steps', 'question_options', 'questions', 'quiz_attempt_grants', 'quiz_attempts', 'quiz_questions', 'quiz_user_results', 'quizzes', 'recipient_target_sources', 'role_functions', 'roles', 'teams', 'training_fields', 'training_programs', 'user_roles', 'users', 'video_watch_ranges']::text[])) THEN
        RAISE EXCEPTION 'Bảng ngoài allowlist: %', p_table;
    END IF;
    IF EXISTS (SELECT 1 FROM jsonb_object_keys(p_data) c(name) WHERE NOT EXISTS (
        SELECT 1 FROM pg_attribute a WHERE a.attrelid = to_regclass('learning_hub.' || p_table)
          AND a.attname = c.name AND a.attnum > 0 AND NOT a.attisdropped
    )) THEN
        RAISE EXCEPTION 'Schema không khớp script ở bảng %; không tự sửa schema.', p_table;
    END IF;
    SELECT string_agg(format('%I', name), ', ' ORDER BY name),
           string_agg(format('x.%I', name), ', ' ORDER BY name)
    INTO v_columns, v_values FROM jsonb_object_keys(p_data) c(name);
    EXECUTE format('INSERT INTO learning_hub.%I AS target (%s)
                    SELECT %s FROM jsonb_populate_record(NULL::learning_hub.%I, $1) x
                    RETURNING to_jsonb(target)', p_table, v_columns, v_values, p_table)
    INTO STRICT v_row USING p_data;
    SELECT jsonb_object_agg(a.attname, v_row->a.attname) INTO v_key
    FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
    WHERE i.indrelid = to_regclass('learning_hub.' || p_table) AND i.indisprimary;
    IF v_key IS NULL THEN RAISE EXCEPTION 'Bảng % không có PK.', p_table; END IF;
    INSERT INTO pg_temp.showcase_inserted(table_name, key_json) VALUES (p_table, v_key);
    RETURN v_row;
END;
$fn$;

DO $seed$
DECLARE
    v_now timestamptz := transaction_timestamp();
    v_ids jsonb := '{}'::jsonb;
    v_row jsonb;
    v_manifest jsonb := '[]'::jsonb;
    v_item record;
    v_table text;
    v_count bigint;
    v_existing jsonb;
BEGIN
    FOREACH v_table IN ARRAY ARRAY['asset_upload_parts', 'assets', 'assignment_recipients', 'assignment_targets', 'attempt_answers', 'attempt_competency_scores', 'attempt_questions', 'attempt_regrades', 'audit_log', 'block_assets', 'block_progress', 'business_modules', 'community_ai_feedback', 'community_ai_turn_sources', 'community_ai_turns', 'community_answers', 'community_asset_upload_parts', 'community_assets', 'community_badge_awards', 'community_badges', 'community_collection_access_grants', 'community_collection_items', 'community_collections', 'community_comments', 'community_content_access_grants', 'community_content_guilds', 'community_content_links', 'community_content_revisions', 'community_content_tags', 'community_content_views', 'community_contents', 'community_contribution_events', 'community_guild_members', 'community_guilds', 'community_help_requests', 'community_knowledge_requests', 'community_learning_links', 'community_maintenance_reviews', 'community_media_transcripts', 'community_mentions', 'community_moderation_actions', 'community_moderators', 'community_notification_digest_batches', 'community_notification_digest_items', 'community_notifications', 'community_outbox_events', 'community_question_links', 'community_questions', 'community_radar_entries', 'community_reports', 'community_review_invitations', 'community_review_proposals', 'community_review_threads', 'community_revision_approvals', 'community_revision_assets', 'community_search_chunks', 'community_subscriptions', 'community_tag_aliases', 'community_tags', 'community_user_expertise', 'community_user_preferences', 'community_votes', 'community_wiki_pages', 'competencies', 'course_assessments', 'course_assignments', 'course_chapters', 'course_completion_rules', 'course_enrollments', 'course_lessons', 'course_objectives', 'course_revisions', 'course_shares', 'courses', 'functions', 'knowledge_document_chunks', 'knowledge_documents', 'learning_activity_days', 'learning_group_members', 'learning_groups', 'learning_reminders', 'learning_user_leases', 'lesson_blocks', 'lesson_bookmarks', 'lesson_feedback', 'lesson_notes', 'lesson_progress', 'orgs', 'process_steps', 'question_options', 'questions', 'quiz_attempt_grants', 'quiz_attempts', 'quiz_questions', 'quiz_user_results', 'quizzes', 'recipient_target_sources', 'role_functions', 'roles', 'teams', 'training_fields', 'training_programs', 'user_roles', 'users', 'video_watch_ranges']::text[] LOOP
        IF to_regclass('learning_hub.' || v_table) IS NULL THEN
            RAISE EXCEPTION 'Thiếu bảng learning_hub.%; kiểm tra baseline/upgrade đã triển khai.', v_table;
        END IF;
    END LOOP;
    SELECT details INTO v_existing FROM learning_hub.audit_log WHERE action = 'DEMO_SHOWCASE_INSTALLED' AND details->>'namespace' = 'demo-showcase-v1';
    IF FOUND THEN
        IF (SELECT count(DISTINCT x->>'table') FROM jsonb_array_elements(v_existing->'rows') x) <> 105 THEN
            RAISE EXCEPTION 'Marker DEMO không đầy đủ; không ghi đè dữ liệu đã có.';
        END IF;
        RAISE NOTICE 'demo-showcase-v1 đã tồn tại. Không INSERT/UPDATE, không làm mới ngày.';
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.orgs WHERE code LIKE 'DEMO-SHOWCASE-%')
       OR EXISTS (SELECT 1 FROM learning_hub.users WHERE user_id LIKE 'DMSH%'
                  OR user_name LIKE 'demo.showcase.%')
       OR EXISTS (SELECT 1 FROM learning_hub.roles WHERE code = 'DEMO_SHOWCASE_INACTIVE')
       OR EXISTS (SELECT 1 FROM learning_hub.functions WHERE code = 'DEMO_SHOWCASE_INACTIVE') THEN
        RAISE EXCEPTION 'Namespace DEMO đã được sử dụng nhưng không có marker; dừng, không UPSERT.';
    END IF;

    -- Đơn vị DEMO, hồ sơ khóa đăng nhập và danh mục không cấp quyền
    v_row := pg_temp.showcase_put('orgs', jsonb_build_object(
        'org_type', 'UNIT',
        'code', 'DEMO-SHOWCASE-V1',
        'name', '[DEMO] Học viện Core Banking',
        'status', 'active',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('org', v_row->'org_id');
    v_row := pg_temp.showcase_put('orgs', jsonb_build_object(
        'parent_id', (v_ids->>'org')::bigint,
        'org_type', 'DEPARTMENT',
        'code', 'DEMO-SHOWCASE-OPS',
        'name', '[DEMO] Phòng vận hành',
        'status', 'active',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('department', v_row->'org_id');
    v_row := pg_temp.showcase_put('teams', jsonb_build_object(
        'org_id', (v_ids->>'org')::bigint,
        'code', 'DEMO-SHOWCASE-TEAM',
        'name', '[DEMO] Nhóm đào tạo nền tảng',
        'description', 'Dữ liệu tổng hợp, không phải đơn vị nghiệp vụ thật.',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('team', v_row->'team_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0001',
        'user_name', 'demo.showcase.01',
        'email', 'showcase1@example.invalid',
        'full_name', '[DEMO] Nguyễn Minh Anh',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Giảng viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u1', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0002',
        'user_name', 'demo.showcase.02',
        'email', 'showcase2@example.invalid',
        'full_name', '[DEMO] Trần Hoàng Nam',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u2', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0003',
        'user_name', 'demo.showcase.03',
        'email', 'showcase3@example.invalid',
        'full_name', '[DEMO] Lê Thu Hà',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u3', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0004',
        'user_name', 'demo.showcase.04',
        'email', 'showcase4@example.invalid',
        'full_name', '[DEMO] Phạm Quang Huy',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u4', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0005',
        'user_name', 'demo.showcase.05',
        'email', 'showcase5@example.invalid',
        'full_name', '[DEMO] Vũ Ngọc Linh',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u5', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0006',
        'user_name', 'demo.showcase.06',
        'email', 'showcase6@example.invalid',
        'full_name', '[DEMO] Đặng Hải Đăng',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u6', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0007',
        'user_name', 'demo.showcase.07',
        'email', 'showcase7@example.invalid',
        'full_name', '[DEMO] Bùi Thanh Mai',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u7', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0008',
        'user_name', 'demo.showcase.08',
        'email', 'showcase8@example.invalid',
        'full_name', '[DEMO] Đỗ Tuấn Kiệt',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u8', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0009',
        'user_name', 'demo.showcase.09',
        'email', 'showcase9@example.invalid',
        'full_name', '[DEMO] Hồ Phương Thảo',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u9', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0010',
        'user_name', 'demo.showcase.10',
        'email', 'showcase10@example.invalid',
        'full_name', '[DEMO] Ngô Đức Anh',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u10', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0011',
        'user_name', 'demo.showcase.11',
        'email', 'showcase11@example.invalid',
        'full_name', '[DEMO] Dương Khánh Vy',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u11', v_row->'user_id');
    v_row := pg_temp.showcase_put('users', jsonb_build_object(
        'user_id', 'DMSH0012',
        'user_name', 'demo.showcase.12',
        'email', 'showcase12@example.invalid',
        'full_name', '[DEMO] Đinh Gia Bảo',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'position_name', 'Học viên minh họa',
        'auth_provider', 'SSO',
        'status', 'locked',
        'must_change_password', true,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('u12', v_row->'user_id');
    v_row := pg_temp.showcase_put('roles', jsonb_build_object(
        'code', 'DEMO_SHOWCASE_INACTIVE',
        'name', '[DEMO] Vai trò không cấp quyền',
        'description', 'Danh mục minh họa bị vô hiệu hóa.',
        'active', false
    ));
    v_ids := v_ids || jsonb_build_object('role', v_row->'role_id');
    v_row := pg_temp.showcase_put('functions', jsonb_build_object(
        'code', 'DEMO_SHOWCASE_INACTIVE',
        'name', '[DEMO] Chức năng không cấp quyền',
        'active', false
    ));
    v_ids := v_ids || jsonb_build_object('function', v_row->'function_id');
    v_row := pg_temp.showcase_put('role_functions', jsonb_build_object(
        'role_id', (v_ids->>'role')::bigint,
        'function_id', (v_ids->>'function')::bigint
    ));
    v_ids := v_ids || jsonb_build_object('rolefunction', v_row->'id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0001',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur0', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0002',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur1', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0003',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur2', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0004',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur3', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0005',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur4', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0006',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur5', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0007',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur6', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0008',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur7', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0009',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur8', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0010',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur9', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0011',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur10', v_row->'ur_id');
    v_row := pg_temp.showcase_put('user_roles', jsonb_build_object(
        'user_id', 'DMSH0012',
        'role_id', (v_ids->>'role')::bigint,
        'scope', 'OWN',
        'active', false,
        'granted_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ur11', v_row->'ur_id');
    v_row := pg_temp.showcase_put('audit_log', jsonb_build_object(
        'actor_id', 'DMSH0001',
        'action', 'DEMO_SHOWCASE_SAMPLE',
        'entity_type', 'DEMO',
        'entity_id', 'showcase-v1',
        'details', '{"synthetic":true,"note":"Bản ghi mẫu, không phải lịch sử thao tác của người dùng thật."}'::jsonb,
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('sampleaudit', v_row->'audit_id');

    -- Khóa học text/process có thể đọc; bài media nháp không trỏ file giả
    v_row := pg_temp.showcase_put('training_fields', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'code', 'DEMO-SHOWCASE-FIELD',
        'name', '[DEMO] Nghiệp vụ Core Banking',
        'description', 'Tình huống đào tạo tổng hợp.',
        'status', 'published',
        'sort_no', 10,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('field', v_row->'field_id');
    v_row := pg_temp.showcase_put('training_programs', jsonb_build_object(
        'field_id', (v_ids->>'field')::bigint,
        'owner_org_id', (v_ids->>'org')::bigint,
        'code', 'DEMO-SHOWCASE-PROGRAM',
        'name', '[DEMO] Lộ trình cán bộ mới',
        'description', 'Từ tổng quan đến vận hành và kiểm soát.',
        'status', 'published',
        'sort_no', 10,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('program', v_row->'program_id');
    v_row := pg_temp.showcase_put('business_modules', jsonb_build_object(
        'field_id', (v_ids->>'field')::bigint,
        'code', 'DEMO-SHOWCASE-MODULE',
        'name', '[DEMO] Vận hành và kiểm soát',
        'status', 'published',
        'sort_no', 10,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('module', v_row->'module_id');
    v_row := pg_temp.showcase_put('learning_groups', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'code', 'DEMO-SHOWCASE-GROUP',
        'name', '[DEMO] Cán bộ mới',
        'description', 'Nhóm học tập tổng hợp, không chứa người dùng thật.',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('group', v_row->'group_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0001',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm0', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0002',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm1', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0003',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm2', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0004',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm3', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0005',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm4', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0006',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm5', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0007',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm6', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0008',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm7', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0009',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm8', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0010',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm9', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0011',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm10', v_row->'member_id');
    v_row := pg_temp.showcase_put('learning_group_members', jsonb_build_object(
        'group_id', (v_ids->>'group')::bigint,
        'user_id', 'DMSH0012',
        'joined_at', v_now - interval '20 days',
        'added_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('gm11', v_row->'member_id');
    v_row := pg_temp.showcase_put('courses', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-C1',
        'owner_org_id', (v_ids->>'org')::bigint,
        'owner_user_id', 'DMSH0001',
        'owner_team_id', (v_ids->>'team')::bigint,
        'lifecycle_status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('course1', v_row->'course_id');
    v_row := pg_temp.showcase_put('course_revisions', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_no', 1,
        'state', 'published',
        'program_id', (v_ids->>'program')::bigint,
        'module_id', (v_ids->>'module')::bigint,
        'title', '[DEMO] Tổng quan hệ thống Core Banking',
        'summary', 'Khóa học minh họa về tổng quan hệ thống core banking. Nội dung tổng hợp, không thay thế quy trình chính thức.',
        'level', 'basic',
        'default_requirement', 'mandatory',
        'published_at', v_now - interval '20 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('revision1', v_row->'revision_id');
    UPDATE learning_hub.courses SET published_revision_id = (v_ids->>'revision1')::bigint
    WHERE course_id = (v_ids->>'course1')::bigint;
    v_row := pg_temp.showcase_put('course_objectives', jsonb_build_object(
        'revision_id', (v_ids->>'revision1')::bigint,
        'description', 'Nhận diện các bước kiểm tra và cách lưu bằng chứng trong tình huống tổng quan hệ thống core banking.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('objective1', v_row->'objective_id');
    v_row := pg_temp.showcase_put('course_chapters', jsonb_build_object(
        'revision_id', (v_ids->>'revision1')::bigint,
        'title', 'Nền tảng và tình huống thực hành',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('chapter1', v_row->'chapter_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter1')::bigint,
        'title', 'Khái niệm và phạm vi',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson1_1', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'block_type', 'text',
        'title', 'Tổng quan hệ thống Core Banking',
        'body', '## Tổng quan hệ thống Core Banking

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block1_1', v_row->'block_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter1')::bigint,
        'title', 'Quy trình kiểm tra tình huống',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson1_2', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'block_type', 'process',
        'title', 'Các bước thực hành',
        'body', '## Tổng quan hệ thống Core Banking

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block1_2', v_row->'block_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block1_2')::bigint,
        'title', 'Xác nhận thông tin đầu vào',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step1_0', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block1_2')::bigint,
        'title', 'Kiểm tra kết quả và lưu bằng chứng',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step1_1', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block1_2')::bigint,
        'title', 'Tổng hợp kết luận',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step1_2', v_row->'step_id');
    v_row := pg_temp.showcase_put('course_completion_rules', jsonb_build_object(
        'revision_id', (v_ids->>'revision1')::bigint,
        'require_all_required_lessons', true,
        'reward_points', 20,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('rule1', v_row->'completion_rule_id');
    v_row := pg_temp.showcase_put('course_shares', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'org_id', (v_ids->>'department')::bigint,
        'include_descendants', false,
        'can_assign', true,
        'can_self_enroll', true,
        'status', 'active',
        'shared_by', 'DMSH0001',
        'shared_at', v_now - interval '15 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('share1', v_row->'share_id');
    v_row := pg_temp.showcase_put('course_assignments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'assigning_org_id', (v_ids->>'org')::bigint,
        'assigned_by', 'DMSH0001',
        'title', '[DEMO] Đợt đào tạo 1',
        'start_at', v_now - interval '14 days',
        'due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'enrollment_mode', 'continue',
        'reminder_enabled', false,
        'reminder_days_before', 3,
        'status', 'active',
        'activated_at', v_now - interval '14 days',
        'idempotency_key', 'showcase-v1-assignment-1',
        'request_hash', repeat('a', 64),
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assignment1', v_row->'assignment_id');
    v_row := pg_temp.showcase_put('assignment_targets', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'target_type', 'learning_group',
        'group_id', (v_ids->>'group')::bigint,
        'include_descendants', false,
        'target_name_snapshot', '[DEMO] Cán bộ mới',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('target1', v_row->'target_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0001',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_1', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0001',
        'enrollment_id', (v_ids->>'enrollment1_1')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_1', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_1')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_1', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0002',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson1_1')::bigint,
        'last_block_id', (v_ids->>'block1_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_2', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment1_2')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_2', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_2')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_2', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_2')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_2_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_2')::bigint,
        'block_id', (v_ids->>'block1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_2_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_2')::bigint,
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_2_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_2')::bigint,
        'block_id', (v_ids->>'block1_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_2_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0003',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson1_1')::bigint,
        'last_block_id', (v_ids->>'block1_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_3', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment1_3')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_3', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_3')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_3', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_3')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_3_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_3')::bigint,
        'block_id', (v_ids->>'block1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_3_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_3')::bigint,
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_3_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_3')::bigint,
        'block_id', (v_ids->>'block1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_3_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0004',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson1_1')::bigint,
        'last_block_id', (v_ids->>'block1_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_4', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment1_4')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_4', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_4')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_4', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_4')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_4_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_4')::bigint,
        'block_id', (v_ids->>'block1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_4_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_4')::bigint,
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_4_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_4')::bigint,
        'block_id', (v_ids->>'block1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_4_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0005',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson1_1')::bigint,
        'last_block_id', (v_ids->>'block1_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_5', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0005',
        'enrollment_id', (v_ids->>'enrollment1_5')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_5', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_5')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_5', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_5')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_5_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_5')::bigint,
        'block_id', (v_ids->>'block1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_5_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_5')::bigint,
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_5_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_5')::bigint,
        'block_id', (v_ids->>'block1_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_5_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0006',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson1_1')::bigint,
        'last_block_id', (v_ids->>'block1_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_6', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0006',
        'enrollment_id', (v_ids->>'enrollment1_6')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_6', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_6')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_6', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_6')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_6_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_6')::bigint,
        'block_id', (v_ids->>'block1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_6_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_6')::bigint,
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_6_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_6')::bigint,
        'block_id', (v_ids->>'block1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_6_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0007',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_7', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0007',
        'enrollment_id', (v_ids->>'enrollment1_7')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_7', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_7')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_7', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0008',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson1_1')::bigint,
        'last_block_id', (v_ids->>'block1_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_8', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0008',
        'enrollment_id', (v_ids->>'enrollment1_8')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_8', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_8')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_8', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_8')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_8_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_8')::bigint,
        'block_id', (v_ids->>'block1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_8_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_8')::bigint,
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_8_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_8')::bigint,
        'block_id', (v_ids->>'block1_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_8_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0009',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson1_1')::bigint,
        'last_block_id', (v_ids->>'block1_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_9', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0009',
        'enrollment_id', (v_ids->>'enrollment1_9')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_9', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_9')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_9', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_9')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_9_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_9')::bigint,
        'block_id', (v_ids->>'block1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_9_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_9')::bigint,
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_9_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_9')::bigint,
        'block_id', (v_ids->>'block1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_9_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0010',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_10', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0010',
        'enrollment_id', (v_ids->>'enrollment1_10')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_10', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_10')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_10', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0011',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson1_1')::bigint,
        'last_block_id', (v_ids->>'block1_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_11', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0011',
        'enrollment_id', (v_ids->>'enrollment1_11')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_11', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_11')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_11', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_11')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_11_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_11')::bigint,
        'block_id', (v_ids->>'block1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_11_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_11')::bigint,
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_11_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_11')::bigint,
        'block_id', (v_ids->>'block1_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_11_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'revision_id', (v_ids->>'revision1')::bigint,
        'user_id', 'DMSH0012',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson1_1')::bigint,
        'last_block_id', (v_ids->>'block1_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment1_12', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment1')::bigint,
        'user_id', 'DMSH0012',
        'enrollment_id', (v_ids->>'enrollment1_12')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '10 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient1_12', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_12')::bigint,
        'target_id', (v_ids->>'target1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source1_12', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_12')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_12_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_12')::bigint,
        'block_id', (v_ids->>'block1_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_12_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_12')::bigint,
        'lesson_id', (v_ids->>'lesson1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp1_12_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment1_12')::bigint,
        'block_id', (v_ids->>'block1_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp1_12_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('courses', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-C2',
        'owner_org_id', (v_ids->>'org')::bigint,
        'owner_user_id', 'DMSH0001',
        'owner_team_id', (v_ids->>'team')::bigint,
        'lifecycle_status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('course2', v_row->'course_id');
    v_row := pg_temp.showcase_put('course_revisions', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_no', 1,
        'state', 'published',
        'program_id', (v_ids->>'program')::bigint,
        'module_id', (v_ids->>'module')::bigint,
        'title', '[DEMO] Quản lý thông tin khách hàng CIF',
        'summary', 'Khóa học minh họa về quản lý thông tin khách hàng cif. Nội dung tổng hợp, không thay thế quy trình chính thức.',
        'level', 'basic',
        'default_requirement', 'mandatory',
        'published_at', v_now - interval '20 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('revision2', v_row->'revision_id');
    UPDATE learning_hub.courses SET published_revision_id = (v_ids->>'revision2')::bigint
    WHERE course_id = (v_ids->>'course2')::bigint;
    v_row := pg_temp.showcase_put('course_objectives', jsonb_build_object(
        'revision_id', (v_ids->>'revision2')::bigint,
        'description', 'Nhận diện các bước kiểm tra và cách lưu bằng chứng trong tình huống quản lý thông tin khách hàng cif.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('objective2', v_row->'objective_id');
    v_row := pg_temp.showcase_put('course_chapters', jsonb_build_object(
        'revision_id', (v_ids->>'revision2')::bigint,
        'title', 'Nền tảng và tình huống thực hành',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('chapter2', v_row->'chapter_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter2')::bigint,
        'title', 'Khái niệm và phạm vi',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson2_1', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'block_type', 'text',
        'title', 'Quản lý thông tin khách hàng CIF',
        'body', '## Quản lý thông tin khách hàng CIF

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block2_1', v_row->'block_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter2')::bigint,
        'title', 'Quy trình kiểm tra tình huống',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson2_2', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'block_type', 'process',
        'title', 'Các bước thực hành',
        'body', '## Quản lý thông tin khách hàng CIF

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block2_2', v_row->'block_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block2_2')::bigint,
        'title', 'Xác nhận thông tin đầu vào',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step2_0', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block2_2')::bigint,
        'title', 'Kiểm tra kết quả và lưu bằng chứng',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step2_1', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block2_2')::bigint,
        'title', 'Tổng hợp kết luận',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step2_2', v_row->'step_id');
    v_row := pg_temp.showcase_put('course_completion_rules', jsonb_build_object(
        'revision_id', (v_ids->>'revision2')::bigint,
        'require_all_required_lessons', true,
        'reward_points', 20,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('rule2', v_row->'completion_rule_id');
    v_row := pg_temp.showcase_put('course_shares', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'org_id', (v_ids->>'department')::bigint,
        'include_descendants', false,
        'can_assign', true,
        'can_self_enroll', true,
        'status', 'active',
        'shared_by', 'DMSH0001',
        'shared_at', v_now - interval '15 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('share2', v_row->'share_id');
    v_row := pg_temp.showcase_put('course_assignments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'assigning_org_id', (v_ids->>'org')::bigint,
        'assigned_by', 'DMSH0001',
        'title', '[DEMO] Đợt đào tạo 2',
        'start_at', v_now - interval '14 days',
        'due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'enrollment_mode', 'continue',
        'reminder_enabled', false,
        'reminder_days_before', 3,
        'status', 'active',
        'activated_at', v_now - interval '14 days',
        'idempotency_key', 'showcase-v1-assignment-2',
        'request_hash', repeat('a', 64),
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assignment2', v_row->'assignment_id');
    v_row := pg_temp.showcase_put('assignment_targets', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'target_type', 'learning_group',
        'group_id', (v_ids->>'group')::bigint,
        'include_descendants', false,
        'target_name_snapshot', '[DEMO] Cán bộ mới',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('target2', v_row->'target_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0001',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson2_1')::bigint,
        'last_block_id', (v_ids->>'block2_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_1', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0001',
        'enrollment_id', (v_ids->>'enrollment2_1')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_1', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_1')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_1', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_1')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_1_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_1')::bigint,
        'block_id', (v_ids->>'block2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_1_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_1')::bigint,
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_1_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_1')::bigint,
        'block_id', (v_ids->>'block2_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_1_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0002',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson2_1')::bigint,
        'last_block_id', (v_ids->>'block2_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_2', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment2_2')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_2', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_2')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_2', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_2')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_2_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_2')::bigint,
        'block_id', (v_ids->>'block2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_2_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_2')::bigint,
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_2_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_2')::bigint,
        'block_id', (v_ids->>'block2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_2_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0003',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson2_1')::bigint,
        'last_block_id', (v_ids->>'block2_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_3', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment2_3')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_3', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_3')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_3', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_3')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_3_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_3')::bigint,
        'block_id', (v_ids->>'block2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_3_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_3')::bigint,
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_3_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_3')::bigint,
        'block_id', (v_ids->>'block2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_3_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0004',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson2_1')::bigint,
        'last_block_id', (v_ids->>'block2_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_4', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment2_4')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_4', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_4')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_4', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_4')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_4_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_4')::bigint,
        'block_id', (v_ids->>'block2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_4_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_4')::bigint,
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_4_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_4')::bigint,
        'block_id', (v_ids->>'block2_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_4_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0005',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson2_1')::bigint,
        'last_block_id', (v_ids->>'block2_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_5', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0005',
        'enrollment_id', (v_ids->>'enrollment2_5')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_5', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_5')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_5', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_5')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_5_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_5')::bigint,
        'block_id', (v_ids->>'block2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_5_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_5')::bigint,
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_5_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_5')::bigint,
        'block_id', (v_ids->>'block2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_5_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0006',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_6', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0006',
        'enrollment_id', (v_ids->>'enrollment2_6')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_6', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_6')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_6', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0007',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson2_1')::bigint,
        'last_block_id', (v_ids->>'block2_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_7', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0007',
        'enrollment_id', (v_ids->>'enrollment2_7')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_7', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_7')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_7', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_7')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_7_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_7')::bigint,
        'block_id', (v_ids->>'block2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_7_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_7')::bigint,
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_7_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_7')::bigint,
        'block_id', (v_ids->>'block2_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_7_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0008',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson2_1')::bigint,
        'last_block_id', (v_ids->>'block2_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_8', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0008',
        'enrollment_id', (v_ids->>'enrollment2_8')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_8', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_8')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_8', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_8')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_8_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_8')::bigint,
        'block_id', (v_ids->>'block2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_8_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_8')::bigint,
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_8_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_8')::bigint,
        'block_id', (v_ids->>'block2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_8_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0009',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_9', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0009',
        'enrollment_id', (v_ids->>'enrollment2_9')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_9', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_9')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_9', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0010',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson2_1')::bigint,
        'last_block_id', (v_ids->>'block2_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_10', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0010',
        'enrollment_id', (v_ids->>'enrollment2_10')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_10', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_10')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_10', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_10')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_10_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_10')::bigint,
        'block_id', (v_ids->>'block2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_10_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_10')::bigint,
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_10_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_10')::bigint,
        'block_id', (v_ids->>'block2_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_10_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0011',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson2_1')::bigint,
        'last_block_id', (v_ids->>'block2_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_11', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0011',
        'enrollment_id', (v_ids->>'enrollment2_11')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_11', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_11')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_11', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_11')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_11_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_11')::bigint,
        'block_id', (v_ids->>'block2_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_11_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_11')::bigint,
        'lesson_id', (v_ids->>'lesson2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp2_11_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment2_11')::bigint,
        'block_id', (v_ids->>'block2_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp2_11_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'revision_id', (v_ids->>'revision2')::bigint,
        'user_id', 'DMSH0012',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment2_12', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment2')::bigint,
        'user_id', 'DMSH0012',
        'enrollment_id', (v_ids->>'enrollment2_12')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '13 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient2_12', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient2_12')::bigint,
        'target_id', (v_ids->>'target2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source2_12', v_row->'source_id');
    v_row := pg_temp.showcase_put('courses', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-C3',
        'owner_org_id', (v_ids->>'org')::bigint,
        'owner_user_id', 'DMSH0001',
        'owner_team_id', (v_ids->>'team')::bigint,
        'lifecycle_status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('course3', v_row->'course_id');
    v_row := pg_temp.showcase_put('course_revisions', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_no', 1,
        'state', 'published',
        'program_id', (v_ids->>'program')::bigint,
        'module_id', (v_ids->>'module')::bigint,
        'title', '[DEMO] Nghiệp vụ tiền gửi và lãi suất',
        'summary', 'Khóa học minh họa về nghiệp vụ tiền gửi và lãi suất. Nội dung tổng hợp, không thay thế quy trình chính thức.',
        'level', 'basic',
        'default_requirement', 'mandatory',
        'published_at', v_now - interval '20 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('revision3', v_row->'revision_id');
    UPDATE learning_hub.courses SET published_revision_id = (v_ids->>'revision3')::bigint
    WHERE course_id = (v_ids->>'course3')::bigint;
    v_row := pg_temp.showcase_put('course_objectives', jsonb_build_object(
        'revision_id', (v_ids->>'revision3')::bigint,
        'description', 'Nhận diện các bước kiểm tra và cách lưu bằng chứng trong tình huống nghiệp vụ tiền gửi và lãi suất.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('objective3', v_row->'objective_id');
    v_row := pg_temp.showcase_put('course_chapters', jsonb_build_object(
        'revision_id', (v_ids->>'revision3')::bigint,
        'title', 'Nền tảng và tình huống thực hành',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('chapter3', v_row->'chapter_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter3')::bigint,
        'title', 'Khái niệm và phạm vi',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson3_1', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'block_type', 'text',
        'title', 'Nghiệp vụ tiền gửi và lãi suất',
        'body', '## Nghiệp vụ tiền gửi và lãi suất

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block3_1', v_row->'block_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter3')::bigint,
        'title', 'Quy trình kiểm tra tình huống',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson3_2', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'block_type', 'process',
        'title', 'Các bước thực hành',
        'body', '## Nghiệp vụ tiền gửi và lãi suất

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block3_2', v_row->'block_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block3_2')::bigint,
        'title', 'Xác nhận thông tin đầu vào',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step3_0', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block3_2')::bigint,
        'title', 'Kiểm tra kết quả và lưu bằng chứng',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step3_1', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block3_2')::bigint,
        'title', 'Tổng hợp kết luận',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step3_2', v_row->'step_id');
    v_row := pg_temp.showcase_put('course_completion_rules', jsonb_build_object(
        'revision_id', (v_ids->>'revision3')::bigint,
        'require_all_required_lessons', true,
        'reward_points', 20,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('rule3', v_row->'completion_rule_id');
    v_row := pg_temp.showcase_put('course_shares', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'org_id', (v_ids->>'department')::bigint,
        'include_descendants', false,
        'can_assign', true,
        'can_self_enroll', true,
        'status', 'active',
        'shared_by', 'DMSH0001',
        'shared_at', v_now - interval '15 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('share3', v_row->'share_id');
    v_row := pg_temp.showcase_put('course_assignments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'assigning_org_id', (v_ids->>'org')::bigint,
        'assigned_by', 'DMSH0001',
        'title', '[DEMO] Đợt đào tạo 3',
        'start_at', v_now - interval '14 days',
        'due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'enrollment_mode', 'continue',
        'reminder_enabled', false,
        'reminder_days_before', 3,
        'status', 'active',
        'activated_at', v_now - interval '14 days',
        'idempotency_key', 'showcase-v1-assignment-3',
        'request_hash', repeat('a', 64),
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assignment3', v_row->'assignment_id');
    v_row := pg_temp.showcase_put('assignment_targets', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'target_type', 'learning_group',
        'group_id', (v_ids->>'group')::bigint,
        'include_descendants', false,
        'target_name_snapshot', '[DEMO] Cán bộ mới',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('target3', v_row->'target_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0001',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson3_1')::bigint,
        'last_block_id', (v_ids->>'block3_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_1', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0001',
        'enrollment_id', (v_ids->>'enrollment3_1')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_1', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_1')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_1', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_1')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_1_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_1')::bigint,
        'block_id', (v_ids->>'block3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_1_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_1')::bigint,
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_1_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_1')::bigint,
        'block_id', (v_ids->>'block3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_1_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0002',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson3_1')::bigint,
        'last_block_id', (v_ids->>'block3_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_2', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment3_2')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_2', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_2')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_2', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_2')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_2_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_2')::bigint,
        'block_id', (v_ids->>'block3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_2_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_2')::bigint,
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_2_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_2')::bigint,
        'block_id', (v_ids->>'block3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_2_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0003',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson3_1')::bigint,
        'last_block_id', (v_ids->>'block3_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_3', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment3_3')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_3', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_3')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_3', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_3')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_3_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_3')::bigint,
        'block_id', (v_ids->>'block3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_3_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_3')::bigint,
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_3_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_3')::bigint,
        'block_id', (v_ids->>'block3_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_3_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0004',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson3_1')::bigint,
        'last_block_id', (v_ids->>'block3_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_4', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment3_4')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_4', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_4')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_4', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_4')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_4_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_4')::bigint,
        'block_id', (v_ids->>'block3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_4_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_4')::bigint,
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_4_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_4')::bigint,
        'block_id', (v_ids->>'block3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_4_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0005',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_5', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0005',
        'enrollment_id', (v_ids->>'enrollment3_5')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_5', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_5')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_5', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0006',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson3_1')::bigint,
        'last_block_id', (v_ids->>'block3_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_6', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0006',
        'enrollment_id', (v_ids->>'enrollment3_6')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_6', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_6')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_6', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_6')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_6_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_6')::bigint,
        'block_id', (v_ids->>'block3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_6_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_6')::bigint,
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_6_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_6')::bigint,
        'block_id', (v_ids->>'block3_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_6_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0007',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson3_1')::bigint,
        'last_block_id', (v_ids->>'block3_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_7', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0007',
        'enrollment_id', (v_ids->>'enrollment3_7')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_7', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_7')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_7', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_7')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_7_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_7')::bigint,
        'block_id', (v_ids->>'block3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_7_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_7')::bigint,
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_7_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_7')::bigint,
        'block_id', (v_ids->>'block3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_7_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0008',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_8', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0008',
        'enrollment_id', (v_ids->>'enrollment3_8')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_8', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_8')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_8', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0009',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson3_1')::bigint,
        'last_block_id', (v_ids->>'block3_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_9', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0009',
        'enrollment_id', (v_ids->>'enrollment3_9')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_9', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_9')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_9', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_9')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_9_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_9')::bigint,
        'block_id', (v_ids->>'block3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_9_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_9')::bigint,
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_9_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_9')::bigint,
        'block_id', (v_ids->>'block3_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_9_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0010',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson3_1')::bigint,
        'last_block_id', (v_ids->>'block3_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_10', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0010',
        'enrollment_id', (v_ids->>'enrollment3_10')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_10', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_10')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_10', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_10')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_10_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_10')::bigint,
        'block_id', (v_ids->>'block3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_10_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_10')::bigint,
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_10_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_10')::bigint,
        'block_id', (v_ids->>'block3_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_10_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0011',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_11', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0011',
        'enrollment_id', (v_ids->>'enrollment3_11')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_11', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_11')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_11', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'revision_id', (v_ids->>'revision3')::bigint,
        'user_id', 'DMSH0012',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson3_1')::bigint,
        'last_block_id', (v_ids->>'block3_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment3_12', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment3')::bigint,
        'user_id', 'DMSH0012',
        'enrollment_id', (v_ids->>'enrollment3_12')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '16 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient3_12', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient3_12')::bigint,
        'target_id', (v_ids->>'target3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source3_12', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_12')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_12_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_12')::bigint,
        'block_id', (v_ids->>'block3_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_12_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_12')::bigint,
        'lesson_id', (v_ids->>'lesson3_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp3_12_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment3_12')::bigint,
        'block_id', (v_ids->>'block3_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp3_12_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('courses', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-C4',
        'owner_org_id', (v_ids->>'org')::bigint,
        'owner_user_id', 'DMSH0001',
        'owner_team_id', (v_ids->>'team')::bigint,
        'lifecycle_status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('course4', v_row->'course_id');
    v_row := pg_temp.showcase_put('course_revisions', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_no', 1,
        'state', 'published',
        'program_id', (v_ids->>'program')::bigint,
        'module_id', (v_ids->>'module')::bigint,
        'title', '[DEMO] Thanh toán và đối soát giao dịch',
        'summary', 'Khóa học minh họa về thanh toán và đối soát giao dịch. Nội dung tổng hợp, không thay thế quy trình chính thức.',
        'level', 'intermediate',
        'default_requirement', 'mandatory',
        'published_at', v_now - interval '20 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('revision4', v_row->'revision_id');
    UPDATE learning_hub.courses SET published_revision_id = (v_ids->>'revision4')::bigint
    WHERE course_id = (v_ids->>'course4')::bigint;
    v_row := pg_temp.showcase_put('course_objectives', jsonb_build_object(
        'revision_id', (v_ids->>'revision4')::bigint,
        'description', 'Nhận diện các bước kiểm tra và cách lưu bằng chứng trong tình huống thanh toán và đối soát giao dịch.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('objective4', v_row->'objective_id');
    v_row := pg_temp.showcase_put('course_chapters', jsonb_build_object(
        'revision_id', (v_ids->>'revision4')::bigint,
        'title', 'Nền tảng và tình huống thực hành',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('chapter4', v_row->'chapter_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter4')::bigint,
        'title', 'Khái niệm và phạm vi',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson4_1', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'block_type', 'text',
        'title', 'Thanh toán và đối soát giao dịch',
        'body', '## Thanh toán và đối soát giao dịch

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block4_1', v_row->'block_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter4')::bigint,
        'title', 'Quy trình kiểm tra tình huống',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson4_2', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'block_type', 'process',
        'title', 'Các bước thực hành',
        'body', '## Thanh toán và đối soát giao dịch

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block4_2', v_row->'block_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block4_2')::bigint,
        'title', 'Xác nhận thông tin đầu vào',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step4_0', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block4_2')::bigint,
        'title', 'Kiểm tra kết quả và lưu bằng chứng',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step4_1', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block4_2')::bigint,
        'title', 'Tổng hợp kết luận',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step4_2', v_row->'step_id');
    v_row := pg_temp.showcase_put('course_completion_rules', jsonb_build_object(
        'revision_id', (v_ids->>'revision4')::bigint,
        'require_all_required_lessons', true,
        'reward_points', 20,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('rule4', v_row->'completion_rule_id');
    v_row := pg_temp.showcase_put('course_shares', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'org_id', (v_ids->>'department')::bigint,
        'include_descendants', false,
        'can_assign', true,
        'can_self_enroll', true,
        'status', 'active',
        'shared_by', 'DMSH0001',
        'shared_at', v_now - interval '15 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('share4', v_row->'share_id');
    v_row := pg_temp.showcase_put('course_assignments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'assigning_org_id', (v_ids->>'org')::bigint,
        'assigned_by', 'DMSH0001',
        'title', '[DEMO] Đợt đào tạo 4',
        'start_at', v_now - interval '14 days',
        'due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'enrollment_mode', 'continue',
        'reminder_enabled', false,
        'reminder_days_before', 3,
        'status', 'active',
        'activated_at', v_now - interval '14 days',
        'idempotency_key', 'showcase-v1-assignment-4',
        'request_hash', repeat('a', 64),
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assignment4', v_row->'assignment_id');
    v_row := pg_temp.showcase_put('assignment_targets', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'target_type', 'learning_group',
        'group_id', (v_ids->>'group')::bigint,
        'include_descendants', false,
        'target_name_snapshot', '[DEMO] Cán bộ mới',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('target4', v_row->'target_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0001',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_1', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0001',
        'enrollment_id', (v_ids->>'enrollment4_1')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_1', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_1')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_1', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0002',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson4_1')::bigint,
        'last_block_id', (v_ids->>'block4_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_2', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment4_2')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_2', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_2')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_2', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_2')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_2_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_2')::bigint,
        'block_id', (v_ids->>'block4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_2_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_2')::bigint,
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_2_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_2')::bigint,
        'block_id', (v_ids->>'block4_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_2_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0003',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson4_1')::bigint,
        'last_block_id', (v_ids->>'block4_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_3', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment4_3')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_3', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_3')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_3', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_3')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_3_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_3')::bigint,
        'block_id', (v_ids->>'block4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_3_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_3')::bigint,
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_3_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_3')::bigint,
        'block_id', (v_ids->>'block4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_3_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0004',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson4_1')::bigint,
        'last_block_id', (v_ids->>'block4_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_4', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment4_4')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_4', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_4')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_4', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_4')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_4_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_4')::bigint,
        'block_id', (v_ids->>'block4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_4_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_4')::bigint,
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_4_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_4')::bigint,
        'block_id', (v_ids->>'block4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_4_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0005',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson4_1')::bigint,
        'last_block_id', (v_ids->>'block4_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_5', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0005',
        'enrollment_id', (v_ids->>'enrollment4_5')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_5', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_5')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_5', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_5')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_5_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_5')::bigint,
        'block_id', (v_ids->>'block4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_5_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_5')::bigint,
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_5_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_5')::bigint,
        'block_id', (v_ids->>'block4_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_5_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0006',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson4_1')::bigint,
        'last_block_id', (v_ids->>'block4_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_6', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0006',
        'enrollment_id', (v_ids->>'enrollment4_6')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_6', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_6')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_6', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_6')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_6_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_6')::bigint,
        'block_id', (v_ids->>'block4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_6_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_6')::bigint,
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_6_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_6')::bigint,
        'block_id', (v_ids->>'block4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_6_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0007',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_7', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0007',
        'enrollment_id', (v_ids->>'enrollment4_7')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_7', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_7')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_7', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0008',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson4_1')::bigint,
        'last_block_id', (v_ids->>'block4_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_8', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0008',
        'enrollment_id', (v_ids->>'enrollment4_8')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_8', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_8')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_8', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_8')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_8_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_8')::bigint,
        'block_id', (v_ids->>'block4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_8_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_8')::bigint,
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_8_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_8')::bigint,
        'block_id', (v_ids->>'block4_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_8_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0009',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson4_1')::bigint,
        'last_block_id', (v_ids->>'block4_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_9', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0009',
        'enrollment_id', (v_ids->>'enrollment4_9')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_9', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_9')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_9', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_9')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_9_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_9')::bigint,
        'block_id', (v_ids->>'block4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_9_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_9')::bigint,
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_9_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_9')::bigint,
        'block_id', (v_ids->>'block4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_9_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0010',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_10', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0010',
        'enrollment_id', (v_ids->>'enrollment4_10')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_10', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_10')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_10', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0011',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson4_1')::bigint,
        'last_block_id', (v_ids->>'block4_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_11', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0011',
        'enrollment_id', (v_ids->>'enrollment4_11')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_11', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_11')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_11', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_11')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_11_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_11')::bigint,
        'block_id', (v_ids->>'block4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_11_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_11')::bigint,
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_11_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_11')::bigint,
        'block_id', (v_ids->>'block4_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_11_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'revision_id', (v_ids->>'revision4')::bigint,
        'user_id', 'DMSH0012',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson4_1')::bigint,
        'last_block_id', (v_ids->>'block4_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment4_12', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment4')::bigint,
        'user_id', 'DMSH0012',
        'enrollment_id', (v_ids->>'enrollment4_12')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '19 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient4_12', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient4_12')::bigint,
        'target_id', (v_ids->>'target4')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source4_12', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_12')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_12_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_12')::bigint,
        'block_id', (v_ids->>'block4_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_12_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_12')::bigint,
        'lesson_id', (v_ids->>'lesson4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp4_12_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment4_12')::bigint,
        'block_id', (v_ids->>'block4_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp4_12_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('courses', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-C5',
        'owner_org_id', (v_ids->>'org')::bigint,
        'owner_user_id', 'DMSH0001',
        'owner_team_id', (v_ids->>'team')::bigint,
        'lifecycle_status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('course5', v_row->'course_id');
    v_row := pg_temp.showcase_put('course_revisions', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_no', 1,
        'state', 'published',
        'program_id', (v_ids->>'program')::bigint,
        'module_id', (v_ids->>'module')::bigint,
        'title', '[DEMO] Vận hành cuối ngày EOD/COB',
        'summary', 'Khóa học minh họa về vận hành cuối ngày eod/cob. Nội dung tổng hợp, không thay thế quy trình chính thức.',
        'level', 'intermediate',
        'default_requirement', 'mandatory',
        'published_at', v_now - interval '20 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('revision5', v_row->'revision_id');
    UPDATE learning_hub.courses SET published_revision_id = (v_ids->>'revision5')::bigint
    WHERE course_id = (v_ids->>'course5')::bigint;
    v_row := pg_temp.showcase_put('course_objectives', jsonb_build_object(
        'revision_id', (v_ids->>'revision5')::bigint,
        'description', 'Nhận diện các bước kiểm tra và cách lưu bằng chứng trong tình huống vận hành cuối ngày eod/cob.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('objective5', v_row->'objective_id');
    v_row := pg_temp.showcase_put('course_chapters', jsonb_build_object(
        'revision_id', (v_ids->>'revision5')::bigint,
        'title', 'Nền tảng và tình huống thực hành',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('chapter5', v_row->'chapter_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter5')::bigint,
        'title', 'Khái niệm và phạm vi',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson5_1', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'block_type', 'text',
        'title', 'Vận hành cuối ngày EOD/COB',
        'body', '## Vận hành cuối ngày EOD/COB

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block5_1', v_row->'block_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter5')::bigint,
        'title', 'Quy trình kiểm tra tình huống',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson5_2', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'block_type', 'process',
        'title', 'Các bước thực hành',
        'body', '## Vận hành cuối ngày EOD/COB

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block5_2', v_row->'block_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block5_2')::bigint,
        'title', 'Xác nhận thông tin đầu vào',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step5_0', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block5_2')::bigint,
        'title', 'Kiểm tra kết quả và lưu bằng chứng',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step5_1', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block5_2')::bigint,
        'title', 'Tổng hợp kết luận',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step5_2', v_row->'step_id');
    v_row := pg_temp.showcase_put('course_completion_rules', jsonb_build_object(
        'revision_id', (v_ids->>'revision5')::bigint,
        'require_all_required_lessons', true,
        'reward_points', 20,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('rule5', v_row->'completion_rule_id');
    v_row := pg_temp.showcase_put('course_shares', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'org_id', (v_ids->>'department')::bigint,
        'include_descendants', false,
        'can_assign', true,
        'can_self_enroll', true,
        'status', 'active',
        'shared_by', 'DMSH0001',
        'shared_at', v_now - interval '15 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('share5', v_row->'share_id');
    v_row := pg_temp.showcase_put('course_assignments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'assigning_org_id', (v_ids->>'org')::bigint,
        'assigned_by', 'DMSH0001',
        'title', '[DEMO] Đợt đào tạo 5',
        'start_at', v_now - interval '14 days',
        'due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'enrollment_mode', 'continue',
        'reminder_enabled', false,
        'reminder_days_before', 3,
        'status', 'active',
        'activated_at', v_now - interval '14 days',
        'idempotency_key', 'showcase-v1-assignment-5',
        'request_hash', repeat('a', 64),
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assignment5', v_row->'assignment_id');
    v_row := pg_temp.showcase_put('assignment_targets', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'target_type', 'learning_group',
        'group_id', (v_ids->>'group')::bigint,
        'include_descendants', false,
        'target_name_snapshot', '[DEMO] Cán bộ mới',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('target5', v_row->'target_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0001',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson5_1')::bigint,
        'last_block_id', (v_ids->>'block5_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_1', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0001',
        'enrollment_id', (v_ids->>'enrollment5_1')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_1', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_1')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_1', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_1')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_1_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_1')::bigint,
        'block_id', (v_ids->>'block5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_1_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_1')::bigint,
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_1_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_1')::bigint,
        'block_id', (v_ids->>'block5_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_1_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0002',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson5_1')::bigint,
        'last_block_id', (v_ids->>'block5_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_2', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment5_2')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_2', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_2')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_2', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_2')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_2_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_2')::bigint,
        'block_id', (v_ids->>'block5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_2_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_2')::bigint,
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_2_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_2')::bigint,
        'block_id', (v_ids->>'block5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_2_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0003',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson5_1')::bigint,
        'last_block_id', (v_ids->>'block5_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_3', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment5_3')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_3', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_3')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_3', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_3')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_3_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_3')::bigint,
        'block_id', (v_ids->>'block5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_3_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_3')::bigint,
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_3_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_3')::bigint,
        'block_id', (v_ids->>'block5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_3_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0004',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson5_1')::bigint,
        'last_block_id', (v_ids->>'block5_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_4', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment5_4')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_4', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_4')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_4', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_4')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_4_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_4')::bigint,
        'block_id', (v_ids->>'block5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_4_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_4')::bigint,
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_4_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_4')::bigint,
        'block_id', (v_ids->>'block5_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_4_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0005',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson5_1')::bigint,
        'last_block_id', (v_ids->>'block5_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_5', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0005',
        'enrollment_id', (v_ids->>'enrollment5_5')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_5', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_5')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_5', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_5')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_5_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_5')::bigint,
        'block_id', (v_ids->>'block5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_5_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_5')::bigint,
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_5_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_5')::bigint,
        'block_id', (v_ids->>'block5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_5_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0006',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_6', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0006',
        'enrollment_id', (v_ids->>'enrollment5_6')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_6', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_6')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_6', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0007',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson5_1')::bigint,
        'last_block_id', (v_ids->>'block5_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_7', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0007',
        'enrollment_id', (v_ids->>'enrollment5_7')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_7', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_7')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_7', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_7')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_7_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_7')::bigint,
        'block_id', (v_ids->>'block5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_7_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_7')::bigint,
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_7_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_7')::bigint,
        'block_id', (v_ids->>'block5_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_7_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0008',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson5_1')::bigint,
        'last_block_id', (v_ids->>'block5_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_8', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0008',
        'enrollment_id', (v_ids->>'enrollment5_8')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_8', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_8')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_8', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_8')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_8_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_8')::bigint,
        'block_id', (v_ids->>'block5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_8_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_8')::bigint,
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_8_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_8')::bigint,
        'block_id', (v_ids->>'block5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_8_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0009',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_9', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0009',
        'enrollment_id', (v_ids->>'enrollment5_9')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_9', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_9')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_9', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0010',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson5_1')::bigint,
        'last_block_id', (v_ids->>'block5_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_10', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0010',
        'enrollment_id', (v_ids->>'enrollment5_10')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_10', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_10')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_10', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_10')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_10_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_10')::bigint,
        'block_id', (v_ids->>'block5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_10_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_10')::bigint,
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_10_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_10')::bigint,
        'block_id', (v_ids->>'block5_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_10_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0011',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson5_1')::bigint,
        'last_block_id', (v_ids->>'block5_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_11', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0011',
        'enrollment_id', (v_ids->>'enrollment5_11')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_11', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_11')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_11', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_11')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_11_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_11')::bigint,
        'block_id', (v_ids->>'block5_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_11_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_11')::bigint,
        'lesson_id', (v_ids->>'lesson5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp5_11_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment5_11')::bigint,
        'block_id', (v_ids->>'block5_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp5_11_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'revision_id', (v_ids->>'revision5')::bigint,
        'user_id', 'DMSH0012',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment5_12', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment5')::bigint,
        'user_id', 'DMSH0012',
        'enrollment_id', (v_ids->>'enrollment5_12')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now - interval '2 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient5_12', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient5_12')::bigint,
        'target_id', (v_ids->>'target5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source5_12', v_row->'source_id');
    v_row := pg_temp.showcase_put('courses', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-C6',
        'owner_org_id', (v_ids->>'org')::bigint,
        'owner_user_id', 'DMSH0001',
        'owner_team_id', (v_ids->>'team')::bigint,
        'lifecycle_status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('course6', v_row->'course_id');
    v_row := pg_temp.showcase_put('course_revisions', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_no', 1,
        'state', 'published',
        'program_id', (v_ids->>'program')::bigint,
        'module_id', (v_ids->>'module')::bigint,
        'title', '[DEMO] Quản lý thay đổi hệ thống',
        'summary', 'Khóa học minh họa về quản lý thay đổi hệ thống. Nội dung tổng hợp, không thay thế quy trình chính thức.',
        'level', 'intermediate',
        'default_requirement', 'mandatory',
        'published_at', v_now - interval '20 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('revision6', v_row->'revision_id');
    UPDATE learning_hub.courses SET published_revision_id = (v_ids->>'revision6')::bigint
    WHERE course_id = (v_ids->>'course6')::bigint;
    v_row := pg_temp.showcase_put('course_objectives', jsonb_build_object(
        'revision_id', (v_ids->>'revision6')::bigint,
        'description', 'Nhận diện các bước kiểm tra và cách lưu bằng chứng trong tình huống quản lý thay đổi hệ thống.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('objective6', v_row->'objective_id');
    v_row := pg_temp.showcase_put('course_chapters', jsonb_build_object(
        'revision_id', (v_ids->>'revision6')::bigint,
        'title', 'Nền tảng và tình huống thực hành',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('chapter6', v_row->'chapter_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter6')::bigint,
        'title', 'Khái niệm và phạm vi',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson6_1', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'block_type', 'text',
        'title', 'Quản lý thay đổi hệ thống',
        'body', '## Quản lý thay đổi hệ thống

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block6_1', v_row->'block_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter6')::bigint,
        'title', 'Quy trình kiểm tra tình huống',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson6_2', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'block_type', 'process',
        'title', 'Các bước thực hành',
        'body', '## Quản lý thay đổi hệ thống

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block6_2', v_row->'block_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block6_2')::bigint,
        'title', 'Xác nhận thông tin đầu vào',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step6_0', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block6_2')::bigint,
        'title', 'Kiểm tra kết quả và lưu bằng chứng',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step6_1', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block6_2')::bigint,
        'title', 'Tổng hợp kết luận',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step6_2', v_row->'step_id');
    v_row := pg_temp.showcase_put('course_completion_rules', jsonb_build_object(
        'revision_id', (v_ids->>'revision6')::bigint,
        'require_all_required_lessons', true,
        'reward_points', 20,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('rule6', v_row->'completion_rule_id');
    v_row := pg_temp.showcase_put('course_shares', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'org_id', (v_ids->>'department')::bigint,
        'include_descendants', false,
        'can_assign', true,
        'can_self_enroll', true,
        'status', 'active',
        'shared_by', 'DMSH0001',
        'shared_at', v_now - interval '15 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('share6', v_row->'share_id');
    v_row := pg_temp.showcase_put('course_assignments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'assigning_org_id', (v_ids->>'org')::bigint,
        'assigned_by', 'DMSH0001',
        'title', '[DEMO] Đợt đào tạo 6',
        'start_at', v_now - interval '14 days',
        'due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'enrollment_mode', 'continue',
        'reminder_enabled', false,
        'reminder_days_before', 3,
        'status', 'active',
        'activated_at', v_now - interval '14 days',
        'idempotency_key', 'showcase-v1-assignment-6',
        'request_hash', repeat('a', 64),
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assignment6', v_row->'assignment_id');
    v_row := pg_temp.showcase_put('assignment_targets', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'target_type', 'learning_group',
        'group_id', (v_ids->>'group')::bigint,
        'include_descendants', false,
        'target_name_snapshot', '[DEMO] Cán bộ mới',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('target6', v_row->'target_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0001',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson6_1')::bigint,
        'last_block_id', (v_ids->>'block6_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_1', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0001',
        'enrollment_id', (v_ids->>'enrollment6_1')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_1', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_1')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_1', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_1')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_1_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_1')::bigint,
        'block_id', (v_ids->>'block6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_1_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_1')::bigint,
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_1_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_1')::bigint,
        'block_id', (v_ids->>'block6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_1_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0002',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson6_1')::bigint,
        'last_block_id', (v_ids->>'block6_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_2', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment6_2')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_2', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_2')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_2', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_2')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_2_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_2')::bigint,
        'block_id', (v_ids->>'block6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_2_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_2')::bigint,
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_2_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_2')::bigint,
        'block_id', (v_ids->>'block6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_2_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0003',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson6_1')::bigint,
        'last_block_id', (v_ids->>'block6_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_3', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment6_3')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_3', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_3')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_3', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_3')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_3_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_3')::bigint,
        'block_id', (v_ids->>'block6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_3_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_3')::bigint,
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_3_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_3')::bigint,
        'block_id', (v_ids->>'block6_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_3_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0004',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson6_1')::bigint,
        'last_block_id', (v_ids->>'block6_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_4', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment6_4')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_4', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_4')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_4', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_4')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_4_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_4')::bigint,
        'block_id', (v_ids->>'block6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_4_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_4')::bigint,
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_4_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_4')::bigint,
        'block_id', (v_ids->>'block6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_4_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0005',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_5', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0005',
        'enrollment_id', (v_ids->>'enrollment6_5')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_5', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_5')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_5', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0006',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson6_1')::bigint,
        'last_block_id', (v_ids->>'block6_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_6', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0006',
        'enrollment_id', (v_ids->>'enrollment6_6')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_6', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_6')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_6', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_6')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_6_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_6')::bigint,
        'block_id', (v_ids->>'block6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_6_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_6')::bigint,
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_6_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_6')::bigint,
        'block_id', (v_ids->>'block6_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_6_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0007',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson6_1')::bigint,
        'last_block_id', (v_ids->>'block6_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_7', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0007',
        'enrollment_id', (v_ids->>'enrollment6_7')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_7', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_7')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_7', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_7')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_7_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_7')::bigint,
        'block_id', (v_ids->>'block6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_7_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_7')::bigint,
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_7_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_7')::bigint,
        'block_id', (v_ids->>'block6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_7_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0008',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_8', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0008',
        'enrollment_id', (v_ids->>'enrollment6_8')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_8', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_8')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_8', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0009',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson6_1')::bigint,
        'last_block_id', (v_ids->>'block6_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_9', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0009',
        'enrollment_id', (v_ids->>'enrollment6_9')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_9', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_9')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_9', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_9')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_9_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_9')::bigint,
        'block_id', (v_ids->>'block6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_9_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_9')::bigint,
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_9_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_9')::bigint,
        'block_id', (v_ids->>'block6_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_9_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0010',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson6_1')::bigint,
        'last_block_id', (v_ids->>'block6_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_10', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0010',
        'enrollment_id', (v_ids->>'enrollment6_10')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_10', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_10')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_10', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_10')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_10_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_10')::bigint,
        'block_id', (v_ids->>'block6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_10_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_10')::bigint,
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_10_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_10')::bigint,
        'block_id', (v_ids->>'block6_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_10_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0011',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_11', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0011',
        'enrollment_id', (v_ids->>'enrollment6_11')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_11', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_11')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_11', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'revision_id', (v_ids->>'revision6')::bigint,
        'user_id', 'DMSH0012',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson6_1')::bigint,
        'last_block_id', (v_ids->>'block6_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment6_12', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment6')::bigint,
        'user_id', 'DMSH0012',
        'enrollment_id', (v_ids->>'enrollment6_12')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '25 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient6_12', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient6_12')::bigint,
        'target_id', (v_ids->>'target6')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source6_12', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_12')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_12_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_12')::bigint,
        'block_id', (v_ids->>'block6_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_12_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_12')::bigint,
        'lesson_id', (v_ids->>'lesson6_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp6_12_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment6_12')::bigint,
        'block_id', (v_ids->>'block6_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp6_12_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('courses', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-C7',
        'owner_org_id', (v_ids->>'org')::bigint,
        'owner_user_id', 'DMSH0001',
        'owner_team_id', (v_ids->>'team')::bigint,
        'lifecycle_status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('course7', v_row->'course_id');
    v_row := pg_temp.showcase_put('course_revisions', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_no', 1,
        'state', 'published',
        'program_id', (v_ids->>'program')::bigint,
        'module_id', (v_ids->>'module')::bigint,
        'title', '[DEMO] Phân quyền và an toàn thông tin',
        'summary', 'Khóa học minh họa về phân quyền và an toàn thông tin. Nội dung tổng hợp, không thay thế quy trình chính thức.',
        'level', 'intermediate',
        'default_requirement', 'mandatory',
        'published_at', v_now - interval '20 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('revision7', v_row->'revision_id');
    UPDATE learning_hub.courses SET published_revision_id = (v_ids->>'revision7')::bigint
    WHERE course_id = (v_ids->>'course7')::bigint;
    v_row := pg_temp.showcase_put('course_objectives', jsonb_build_object(
        'revision_id', (v_ids->>'revision7')::bigint,
        'description', 'Nhận diện các bước kiểm tra và cách lưu bằng chứng trong tình huống phân quyền và an toàn thông tin.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('objective7', v_row->'objective_id');
    v_row := pg_temp.showcase_put('course_chapters', jsonb_build_object(
        'revision_id', (v_ids->>'revision7')::bigint,
        'title', 'Nền tảng và tình huống thực hành',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('chapter7', v_row->'chapter_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter7')::bigint,
        'title', 'Khái niệm và phạm vi',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson7_1', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'block_type', 'text',
        'title', 'Phân quyền và an toàn thông tin',
        'body', '## Phân quyền và an toàn thông tin

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block7_1', v_row->'block_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter7')::bigint,
        'title', 'Quy trình kiểm tra tình huống',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson7_2', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'block_type', 'process',
        'title', 'Các bước thực hành',
        'body', '## Phân quyền và an toàn thông tin

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block7_2', v_row->'block_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block7_2')::bigint,
        'title', 'Xác nhận thông tin đầu vào',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step7_0', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block7_2')::bigint,
        'title', 'Kiểm tra kết quả và lưu bằng chứng',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step7_1', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block7_2')::bigint,
        'title', 'Tổng hợp kết luận',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step7_2', v_row->'step_id');
    v_row := pg_temp.showcase_put('course_completion_rules', jsonb_build_object(
        'revision_id', (v_ids->>'revision7')::bigint,
        'require_all_required_lessons', true,
        'reward_points', 20,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('rule7', v_row->'completion_rule_id');
    v_row := pg_temp.showcase_put('course_shares', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'org_id', (v_ids->>'department')::bigint,
        'include_descendants', false,
        'can_assign', true,
        'can_self_enroll', true,
        'status', 'active',
        'shared_by', 'DMSH0001',
        'shared_at', v_now - interval '15 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('share7', v_row->'share_id');
    v_row := pg_temp.showcase_put('course_assignments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'assigning_org_id', (v_ids->>'org')::bigint,
        'assigned_by', 'DMSH0001',
        'title', '[DEMO] Đợt đào tạo 7',
        'start_at', v_now - interval '14 days',
        'due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'enrollment_mode', 'continue',
        'reminder_enabled', false,
        'reminder_days_before', 3,
        'status', 'active',
        'activated_at', v_now - interval '14 days',
        'idempotency_key', 'showcase-v1-assignment-7',
        'request_hash', repeat('a', 64),
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assignment7', v_row->'assignment_id');
    v_row := pg_temp.showcase_put('assignment_targets', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'target_type', 'learning_group',
        'group_id', (v_ids->>'group')::bigint,
        'include_descendants', false,
        'target_name_snapshot', '[DEMO] Cán bộ mới',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('target7', v_row->'target_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0001',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_1', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0001',
        'enrollment_id', (v_ids->>'enrollment7_1')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_1', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_1')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_1', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0002',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson7_1')::bigint,
        'last_block_id', (v_ids->>'block7_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_2', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment7_2')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_2', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_2')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_2', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_2')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_2_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_2')::bigint,
        'block_id', (v_ids->>'block7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_2_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_2')::bigint,
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_2_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_2')::bigint,
        'block_id', (v_ids->>'block7_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_2_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0003',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson7_1')::bigint,
        'last_block_id', (v_ids->>'block7_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_3', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment7_3')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_3', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_3')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_3', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_3')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_3_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_3')::bigint,
        'block_id', (v_ids->>'block7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_3_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_3')::bigint,
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_3_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_3')::bigint,
        'block_id', (v_ids->>'block7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_3_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0004',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson7_1')::bigint,
        'last_block_id', (v_ids->>'block7_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_4', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment7_4')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_4', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_4')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_4', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_4')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_4_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_4')::bigint,
        'block_id', (v_ids->>'block7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_4_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_4')::bigint,
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_4_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_4')::bigint,
        'block_id', (v_ids->>'block7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_4_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0005',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson7_1')::bigint,
        'last_block_id', (v_ids->>'block7_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_5', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0005',
        'enrollment_id', (v_ids->>'enrollment7_5')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_5', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_5')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_5', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_5')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_5_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_5')::bigint,
        'block_id', (v_ids->>'block7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_5_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_5')::bigint,
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_5_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_5')::bigint,
        'block_id', (v_ids->>'block7_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_5_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0006',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson7_1')::bigint,
        'last_block_id', (v_ids->>'block7_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_6', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0006',
        'enrollment_id', (v_ids->>'enrollment7_6')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_6', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_6')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_6', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_6')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_6_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_6')::bigint,
        'block_id', (v_ids->>'block7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_6_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_6')::bigint,
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_6_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_6')::bigint,
        'block_id', (v_ids->>'block7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_6_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0007',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_7', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0007',
        'enrollment_id', (v_ids->>'enrollment7_7')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_7', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_7')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_7', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0008',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson7_1')::bigint,
        'last_block_id', (v_ids->>'block7_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_8', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0008',
        'enrollment_id', (v_ids->>'enrollment7_8')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_8', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_8')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_8', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_8')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_8_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_8')::bigint,
        'block_id', (v_ids->>'block7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_8_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_8')::bigint,
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_8_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_8')::bigint,
        'block_id', (v_ids->>'block7_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_8_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0009',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson7_1')::bigint,
        'last_block_id', (v_ids->>'block7_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_9', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0009',
        'enrollment_id', (v_ids->>'enrollment7_9')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_9', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_9')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_9', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_9')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_9_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_9')::bigint,
        'block_id', (v_ids->>'block7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_9_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_9')::bigint,
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_9_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_9')::bigint,
        'block_id', (v_ids->>'block7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_9_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0010',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_10', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0010',
        'enrollment_id', (v_ids->>'enrollment7_10')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_10', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_10')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_10', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0011',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson7_1')::bigint,
        'last_block_id', (v_ids->>'block7_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_11', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0011',
        'enrollment_id', (v_ids->>'enrollment7_11')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_11', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_11')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_11', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_11')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_11_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_11')::bigint,
        'block_id', (v_ids->>'block7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_11_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_11')::bigint,
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_11_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_11')::bigint,
        'block_id', (v_ids->>'block7_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_11_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'revision_id', (v_ids->>'revision7')::bigint,
        'user_id', 'DMSH0012',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson7_1')::bigint,
        'last_block_id', (v_ids->>'block7_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment7_12', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment7')::bigint,
        'user_id', 'DMSH0012',
        'enrollment_id', (v_ids->>'enrollment7_12')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '28 days',
        'requirement', 'mandatory',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient7_12', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient7_12')::bigint,
        'target_id', (v_ids->>'target7')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source7_12', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_12')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_12_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_12')::bigint,
        'block_id', (v_ids->>'block7_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_12_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_12')::bigint,
        'lesson_id', (v_ids->>'lesson7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp7_12_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment7_12')::bigint,
        'block_id', (v_ids->>'block7_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp7_12_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('courses', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-C8',
        'owner_org_id', (v_ids->>'org')::bigint,
        'owner_user_id', 'DMSH0001',
        'owner_team_id', (v_ids->>'team')::bigint,
        'lifecycle_status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('course8', v_row->'course_id');
    v_row := pg_temp.showcase_put('course_revisions', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_no', 1,
        'state', 'published',
        'program_id', (v_ids->>'program')::bigint,
        'module_id', (v_ids->>'module')::bigint,
        'title', '[DEMO] Giám sát và xử lý sự cố',
        'summary', 'Khóa học minh họa về giám sát và xử lý sự cố. Nội dung tổng hợp, không thay thế quy trình chính thức.',
        'level', 'intermediate',
        'default_requirement', 'optional',
        'published_at', v_now - interval '20 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('revision8', v_row->'revision_id');
    UPDATE learning_hub.courses SET published_revision_id = (v_ids->>'revision8')::bigint
    WHERE course_id = (v_ids->>'course8')::bigint;
    v_row := pg_temp.showcase_put('course_objectives', jsonb_build_object(
        'revision_id', (v_ids->>'revision8')::bigint,
        'description', 'Nhận diện các bước kiểm tra và cách lưu bằng chứng trong tình huống giám sát và xử lý sự cố.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('objective8', v_row->'objective_id');
    v_row := pg_temp.showcase_put('course_chapters', jsonb_build_object(
        'revision_id', (v_ids->>'revision8')::bigint,
        'title', 'Nền tảng và tình huống thực hành',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('chapter8', v_row->'chapter_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter8')::bigint,
        'title', 'Khái niệm và phạm vi',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson8_1', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'block_type', 'text',
        'title', 'Giám sát và xử lý sự cố',
        'body', '## Giám sát và xử lý sự cố

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block8_1', v_row->'block_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'chapter8')::bigint,
        'title', 'Quy trình kiểm tra tình huống',
        'lesson_type', 'content',
        'estimated_duration_seconds', 600,
        'is_required', true,
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lesson8_2', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'block_type', 'process',
        'title', 'Các bước thực hành',
        'body', '## Giám sát và xử lý sự cố

Đây là tình huống đào tạo tổng hợp.

1. Xác nhận phạm vi công việc và quyền truy cập.
2. Thu thập thông tin trong môi trường thử nghiệm.
3. Đối chiếu kết quả với checklist.
4. Ghi nhận bằng chứng và chuyển người phụ trách khi cần.

Không nhập thông tin khách hàng thật vào bài thực hành.',
        'body_format', 'markdown',
        'completion_mode', 'manual',
        'is_required', true,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('block8_2', v_row->'block_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block8_2')::bigint,
        'title', 'Xác nhận thông tin đầu vào',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step8_0', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block8_2')::bigint,
        'title', 'Kiểm tra kết quả và lưu bằng chứng',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step8_1', v_row->'step_id');
    v_row := pg_temp.showcase_put('process_steps', jsonb_build_object(
        'block_id', (v_ids->>'block8_2')::bigint,
        'title', 'Tổng hợp kết luận',
        'description', 'Thực hiện trong môi trường thử nghiệm với dữ liệu tổng hợp.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('step8_2', v_row->'step_id');
    v_row := pg_temp.showcase_put('course_completion_rules', jsonb_build_object(
        'revision_id', (v_ids->>'revision8')::bigint,
        'require_all_required_lessons', true,
        'reward_points', 20,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('rule8', v_row->'completion_rule_id');
    v_row := pg_temp.showcase_put('course_shares', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'org_id', (v_ids->>'department')::bigint,
        'include_descendants', false,
        'can_assign', true,
        'can_self_enroll', true,
        'status', 'active',
        'shared_by', 'DMSH0001',
        'shared_at', v_now - interval '15 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('share8', v_row->'share_id');
    v_row := pg_temp.showcase_put('course_assignments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'assigning_org_id', (v_ids->>'org')::bigint,
        'assigned_by', 'DMSH0001',
        'title', '[DEMO] Đợt đào tạo 8',
        'start_at', v_now - interval '14 days',
        'due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'enrollment_mode', 'continue',
        'reminder_enabled', false,
        'reminder_days_before', 3,
        'status', 'active',
        'activated_at', v_now - interval '14 days',
        'idempotency_key', 'showcase-v1-assignment-8',
        'request_hash', repeat('a', 64),
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assignment8', v_row->'assignment_id');
    v_row := pg_temp.showcase_put('assignment_targets', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'target_type', 'learning_group',
        'group_id', (v_ids->>'group')::bigint,
        'include_descendants', false,
        'target_name_snapshot', '[DEMO] Cán bộ mới',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('target8', v_row->'target_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0001',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson8_1')::bigint,
        'last_block_id', (v_ids->>'block8_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_1', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0001',
        'enrollment_id', (v_ids->>'enrollment8_1')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_1', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_1')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_1', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_1')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_1_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_1')::bigint,
        'block_id', (v_ids->>'block8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_1_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_1')::bigint,
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_1_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_1')::bigint,
        'block_id', (v_ids->>'block8_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_1_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0002',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson8_1')::bigint,
        'last_block_id', (v_ids->>'block8_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_2', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment8_2')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_2', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_2')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_2', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_2')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_2_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_2')::bigint,
        'block_id', (v_ids->>'block8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_2_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_2')::bigint,
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_2_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_2')::bigint,
        'block_id', (v_ids->>'block8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_2_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0003',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson8_1')::bigint,
        'last_block_id', (v_ids->>'block8_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_3', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment8_3')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_3', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_3')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_3', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_3')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_3_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_3')::bigint,
        'block_id', (v_ids->>'block8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_3_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_3')::bigint,
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_3_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_3')::bigint,
        'block_id', (v_ids->>'block8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_3_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0004',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson8_1')::bigint,
        'last_block_id', (v_ids->>'block8_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_4', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment8_4')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_4', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_4')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_4', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_4')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_4_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_4')::bigint,
        'block_id', (v_ids->>'block8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_4_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_4')::bigint,
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_4_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_4')::bigint,
        'block_id', (v_ids->>'block8_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_4_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0005',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson8_1')::bigint,
        'last_block_id', (v_ids->>'block8_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_5', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0005',
        'enrollment_id', (v_ids->>'enrollment8_5')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_5', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_5')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_5', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_5')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_5_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_5')::bigint,
        'block_id', (v_ids->>'block8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_5_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_5')::bigint,
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_5_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_5')::bigint,
        'block_id', (v_ids->>'block8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_5_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0006',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_6', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0006',
        'enrollment_id', (v_ids->>'enrollment8_6')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_6', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_6')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_6', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0007',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson8_1')::bigint,
        'last_block_id', (v_ids->>'block8_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_7', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0007',
        'enrollment_id', (v_ids->>'enrollment8_7')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_7', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_7')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_7', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_7')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_7_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_7')::bigint,
        'block_id', (v_ids->>'block8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_7_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_7')::bigint,
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_7_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_7')::bigint,
        'block_id', (v_ids->>'block8_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_7_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0008',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson8_1')::bigint,
        'last_block_id', (v_ids->>'block8_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_8', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0008',
        'enrollment_id', (v_ids->>'enrollment8_8')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_8', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_8')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_8', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_8')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_8_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_8')::bigint,
        'block_id', (v_ids->>'block8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_8_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_8')::bigint,
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_8_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_8')::bigint,
        'block_id', (v_ids->>'block8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_8_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0009',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_9', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0009',
        'enrollment_id', (v_ids->>'enrollment8_9')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_9', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_9')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_9', v_row->'source_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0010',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'status', 'completed',
        'progress_percent', 100,
        'last_lesson_id', (v_ids->>'lesson8_1')::bigint,
        'last_block_id', (v_ids->>'block8_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_10', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0010',
        'enrollment_id', (v_ids->>'enrollment8_10')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_10', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_10')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_10', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_10')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_10_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_10')::bigint,
        'block_id', (v_ids->>'block8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_10_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_10')::bigint,
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_10_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_10')::bigint,
        'block_id', (v_ids->>'block8_2')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_10_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0011',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', NULL,
        'status', 'in_progress',
        'progress_percent', 50,
        'last_lesson_id', (v_ids->>'lesson8_1')::bigint,
        'last_block_id', (v_ids->>'block8_1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_11', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0011',
        'enrollment_id', (v_ids->>'enrollment8_11')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_11', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_11')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_11', v_row->'source_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_11')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_11_1', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_11')::bigint,
        'block_id', (v_ids->>'block8_1')::bigint,
        'status', 'completed',
        'progress_percent', 100,
        'started_at', v_now - interval '10 days',
        'last_access_at', v_now - interval '1 days',
        'completed_at', v_now - interval '1 days',
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_11_1', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('lesson_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_11')::bigint,
        'lesson_id', (v_ids->>'lesson8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lp8_11_2', v_row->'lesson_progress_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'enrollment8_11')::bigint,
        'block_id', (v_ids->>'block8_2')::bigint,
        'status', 'not_started',
        'progress_percent', 0,
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'last_event_sequence', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bp8_11_2', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'revision_id', (v_ids->>'revision8')::bigint,
        'user_id', 'DMSH0012',
        'cycle_no', 1,
        'enrollment_origin', 'assigned',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'enrolled_at', v_now - interval '14 days',
        'started_at', NULL,
        'last_access_at', NULL,
        'completed_at', NULL,
        'status', 'not_started',
        'progress_percent', 0,
        'last_lesson_id', NULL,
        'last_block_id', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('enrollment8_12', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('assignment_recipients', jsonb_build_object(
        'assignment_id', (v_ids->>'assignment8')::bigint,
        'user_id', 'DMSH0012',
        'enrollment_id', (v_ids->>'enrollment8_12')::bigint,
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'resolved_at', v_now - interval '14 days',
        'effective_start_at', v_now - interval '14 days',
        'effective_due_at', v_now + interval '31 days',
        'requirement', 'optional',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('recipient8_12', v_row->'recipient_id');
    v_row := pg_temp.showcase_put('recipient_target_sources', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient8_12')::bigint,
        'target_id', (v_ids->>'target8')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('source8_12', v_row->'source_id');
    v_row := pg_temp.showcase_put('learning_reminders', jsonb_build_object(
        'recipient_id', (v_ids->>'recipient1_1')::bigint,
        'scheduled_at', v_now - interval '1 days',
        'due_at_snapshot', v_now + interval '10 days',
        'channel', 'in_app',
        'status', 'cancelled',
        'attempt_count', 0,
        'last_error_code', 'DEMO_NO_DELIVERY',
        'idempotency_key', 'showcase-v1-reminder',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('reminder', v_row->'reminder_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0001',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity1_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0001',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity1_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0001',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity1_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0001',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity1_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0001',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity1_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0002',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity2_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0002',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity2_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0002',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity2_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0002',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity2_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0002',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity2_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0003',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity3_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0003',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity3_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0003',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity3_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0003',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity3_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0003',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity3_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0004',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity4_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0004',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity4_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0004',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity4_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0004',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity4_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0004',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity4_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0005',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity5_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0005',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity5_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0005',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity5_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0005',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity5_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0005',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity5_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0006',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity6_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0006',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity6_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0006',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity6_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0006',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity6_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0006',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity6_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0007',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity7_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0007',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity7_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0007',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity7_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0007',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity7_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0007',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity7_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0008',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity8_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0008',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity8_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0008',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity8_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0008',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity8_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0008',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity8_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0009',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity9_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0009',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity9_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0009',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity9_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0009',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity9_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0009',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity9_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0010',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity10_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0010',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity10_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0010',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity10_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0010',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity10_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0010',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity10_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0011',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity11_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0011',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity11_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0011',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity11_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0011',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity11_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0011',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity11_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0012',
        'activity_date', (v_now - interval '1 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity12_1', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0012',
        'activity_date', (v_now - interval '2 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity12_2', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0012',
        'activity_date', (v_now - interval '3 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity12_3', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0012',
        'activity_date', (v_now - interval '4 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity12_4', v_row->'activity_id');
    v_row := pg_temp.showcase_put('learning_activity_days', jsonb_build_object(
        'user_id', 'DMSH0012',
        'activity_date', (v_now - interval '5 days')::date,
        'first_source', 'block_progress',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('activity12_5', v_row->'activity_id');
    v_row := pg_temp.showcase_put('lesson_notes', jsonb_build_object(
        'user_id', 'DMSH0002',
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'anchor_type', 'none',
        'body', '[DEMO] Cần phân biệt phạm vi nghiệp vụ và phạm vi phân quyền trước khi thực hành.',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('note', v_row->'note_id');
    v_row := pg_temp.showcase_put('lesson_feedback', jsonb_build_object(
        'user_id', 'DMSH0002',
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'course_id', (v_ids->>'course1')::bigint,
        'kind', 'rating',
        'stars', 5,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('feedback', v_row->'feedback_id');
    v_row := pg_temp.showcase_put('lesson_feedback', jsonb_build_object(
        'user_id', 'DMSH0003',
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'course_id', (v_ids->>'course1')::bigint,
        'kind', 'question',
        'anchor_type', 'none',
        'body', '[DEMO] Khi nào cần chuyển tình huống cho người phụ trách?',
        'status', 'answered',
        'reply', '[DEMO] Khi vượt phạm vi xử lý hoặc chưa có đủ bằng chứng.',
        'replied_by', 'DMSH0001',
        'reply_time', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('questionfeedback', v_row->'feedback_id');
    v_row := pg_temp.showcase_put('courses', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-MEDIA',
        'owner_org_id', (v_ids->>'org')::bigint,
        'owner_user_id', 'DMSH0001',
        'lifecycle_status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('mediacourse', v_row->'course_id');
    v_row := pg_temp.showcase_put('course_revisions', jsonb_build_object(
        'course_id', (v_ids->>'mediacourse')::bigint,
        'revision_no', 1,
        'state', 'draft',
        'title', '[DEMO] Bản nháp tình huống tệp chưa sẵn sàng',
        'default_requirement', 'optional',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('mediarevision', v_row->'revision_id');
    v_row := pg_temp.showcase_put('course_chapters', jsonb_build_object(
        'revision_id', (v_ids->>'mediarevision')::bigint,
        'title', 'Media minh họa lỗi',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('mediachapter', v_row->'chapter_id');
    v_row := pg_temp.showcase_put('course_lessons', jsonb_build_object(
        'chapter_id', (v_ids->>'mediachapter')::bigint,
        'title', '[DEMO] Video chưa tải lên',
        'lesson_type', 'content',
        'estimated_duration_seconds', 120,
        'is_required', false,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('medialesson', v_row->'lesson_id');
    v_row := pg_temp.showcase_put('assets', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'uploaded_by', 'DMSH0001',
        'storage_type', 's3',
        'original_name', 'DEMO-khong-co-tep.mp4',
        'mime_type', 'video/mp4',
        'size_bytes', 0,
        'status', 'failed',
        'media_type', 'video',
        'upload_id', gen_random_uuid(),
        'upload_total_bytes', 1024,
        'uploaded_bytes', 0,
        'processing_error_code', 'DEMO_NO_FILE',
        'duration_seconds', 120,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('asset', v_row->'asset_id');
    v_row := pg_temp.showcase_put('asset_upload_parts', jsonb_build_object(
        'upload_id', (SELECT upload_id FROM learning_hub.assets WHERE asset_id=(v_ids->>'asset')::bigint),
        'part_number', 1,
        'size_bytes', 1024,
        'checksum_sha256', repeat('a', 64),
        'part_status', 'uploading',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('lesson_blocks', jsonb_build_object(
        'lesson_id', (v_ids->>'medialesson')::bigint,
        'block_type', 'video',
        'title', '[DEMO] Media nháp, không có đối tượng S3',
        'completion_mode', 'video_coverage',
        'completion_threshold', 80,
        'is_required', false,
        'sort_no', 1,
        'media_asset_id', (v_ids->>'asset')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('mediablock', v_row->'block_id');
    v_row := pg_temp.showcase_put('block_assets', jsonb_build_object(
        'block_id', (v_ids->>'mediablock')::bigint,
        'asset_id', (v_ids->>'asset')::bigint,
        'usage', 'primary',
        'caption', '[DEMO] Không có tệp thật.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('blockasset', v_row->'block_asset_id');
    v_row := pg_temp.showcase_put('course_enrollments', jsonb_build_object(
        'course_id', (v_ids->>'mediacourse')::bigint,
        'revision_id', (v_ids->>'mediarevision')::bigint,
        'user_id', 'DMSH0002',
        'cycle_no', 1,
        'enrollment_origin', 'self',
        'self_enrolled_at', v_now - interval '5 days',
        'enrolled_at', v_now - interval '5 days',
        'org_id_snapshot', (v_ids->>'org')::bigint,
        'team_id_snapshot', (v_ids->>'team')::bigint,
        'status', 'withdrawn',
        'progress_percent', 0,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('mediaenrollment', v_row->'enrollment_id');
    v_row := pg_temp.showcase_put('block_progress', jsonb_build_object(
        'enrollment_id', (v_ids->>'mediaenrollment')::bigint,
        'block_id', (v_ids->>'mediablock')::bigint,
        'media_asset_id', (v_ids->>'asset')::bigint,
        'status', 'in_progress',
        'progress_percent', 25,
        'last_video_position_seconds', 30,
        'watched_seconds', 30,
        'last_event_sequence', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('mediaprogress', v_row->'block_progress_id');
    v_row := pg_temp.showcase_put('video_watch_ranges', jsonb_build_object(
        'block_progress_id', (v_ids->>'mediaprogress')::bigint,
        'start_second', 0,
        'end_second', 30,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('watch', v_row->'watch_range_id');
    v_row := pg_temp.showcase_put('learning_user_leases', jsonb_build_object(
        'user_id', 'DMSH0002',
        'lease_state', 'released',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('lease', v_row->'user_id');
    v_row := pg_temp.showcase_put('lesson_bookmarks', jsonb_build_object(
        'user_id', 'DMSH0002',
        'lesson_id', (v_ids->>'medialesson')::bigint,
        'block_id', (v_ids->>'mediablock')::bigint,
        'anchor_type', 'video',
        'video_second', 30,
        'label', '[DEMO] Mốc minh họa của bản nháp',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('bookmark', v_row->'bookmark_id');

    -- Ngân hàng câu hỏi, bài thi và kết quả tổng hợp nhất quán
    v_row := pg_temp.showcase_put('competencies', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-COMP',
        'name', '[DEMO] Kiểm soát vận hành',
        'description', 'Năng lực minh họa với câu hỏi tổng hợp.',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('competency', v_row->'competency_id');
    v_row := pg_temp.showcase_put('quizzes', jsonb_build_object(
        'course_id', (v_ids->>'course1')::bigint,
        'title', '[DEMO] Kiểm tra Tổng quan hệ thống Core Banking',
        'description', 'Câu hỏi tổng hợp phục vụ minh họa giao diện.',
        'quiz_type', 'FINAL_EXAM',
        'status', 'PUBLISHED',
        'selection_mode', 'FIXED',
        'question_count', 4,
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'published_at', v_now - interval '12 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('quiz1', v_row->'quiz_id');
    v_row := pg_temp.showcase_put('course_assessments', jsonb_build_object(
        'revision_id', (v_ids->>'revision1')::bigint,
        'assessment_revision_id', (v_ids->>'quiz1')::bigint,
        'placement', 'course',
        'is_required', false,
        'pass_score_percent', 75,
        'allow_retake', true,
        'max_attempts', 3,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assessmentlink1', v_row->'link_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q1-1',
        'course_id', (v_ids->>'course1')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q1_1', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q1_1')::bigint,
        'content', 'Phạm vi và quyền xử lý',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct1_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q1_1')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong1_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'question_id', (v_ids->>'q1_1')::bigint,
        'sort_no', 1,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq1_1', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q1-2',
        'course_id', (v_ids->>'course1')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Thông tin nào cần lưu để đối soát kết quả?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q1_2', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q1_2')::bigint,
        'content', 'Thông tin đầu vào và bằng chứng kiểm tra',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct1_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q1_2')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong1_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'question_id', (v_ids->>'q1_2')::bigint,
        'sort_no', 2,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq1_2', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q1-3',
        'course_id', (v_ids->>'course1')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q1_3', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q1_3')::bigint,
        'content', 'Ghi nhận và chuyển người phụ trách',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct1_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q1_3')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong1_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'question_id', (v_ids->>'q1_3')::bigint,
        'sort_no', 3,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq1_3', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q1-4',
        'course_id', (v_ids->>'course1')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'MEDIUM',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q1_4', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q1_4')::bigint,
        'content', 'Chỉ cấp quyền cần thiết',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct1_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q1_4')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong1_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'question_id', (v_ids->>'q1_4')::bigint,
        'sort_no', 4,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq1_4', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment1_2')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 4,
        'raw_score', 4,
        'score_percent', 100,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt1_2', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_2')::bigint,
        'question_id', (v_ids->>'q1_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_2_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_2_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct1_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_2_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_2')::bigint,
        'question_id', (v_ids->>'q1_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_2_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_2_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct1_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_2_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_2')::bigint,
        'question_id', (v_ids->>'q1_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_2_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_2_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct1_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_2_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_2')::bigint,
        'question_id', (v_ids->>'q1_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_2_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_2_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct1_4')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_2_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'user_id', 'DMSH0002',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt1_2')::bigint,
        'last_attempt_id', (v_ids->>'attempt1_2')::bigint,
        'best_score_percent', 100,
        'last_score_percent', 100,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_2')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 4,
        'earned_score', 4,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment1_3')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 3,
        'raw_score', 3,
        'score_percent', 75,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt1_3', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_3')::bigint,
        'question_id', (v_ids->>'q1_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_3_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_3_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct1_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_3_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_3')::bigint,
        'question_id', (v_ids->>'q1_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_3_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_3_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct1_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_3_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_3')::bigint,
        'question_id', (v_ids->>'q1_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_3_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_3_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct1_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_3_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_3')::bigint,
        'question_id', (v_ids->>'q1_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_3_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_3_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong1_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_3_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'user_id', 'DMSH0003',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt1_3')::bigint,
        'last_attempt_id', (v_ids->>'attempt1_3')::bigint,
        'best_score_percent', 75,
        'last_score_percent', 75,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_3')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 3,
        'earned_score', 3,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment1_4')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 2,
        'raw_score', 2,
        'score_percent', 50,
        'passed', false,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt1_4', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_4')::bigint,
        'question_id', (v_ids->>'q1_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_4_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_4_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct1_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_4_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_4')::bigint,
        'question_id', (v_ids->>'q1_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_4_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_4_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct1_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_4_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_4')::bigint,
        'question_id', (v_ids->>'q1_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_4_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_4_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong1_3')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_4_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_4')::bigint,
        'question_id', (v_ids->>'q1_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct1_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct1_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong1_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct1_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq1_4_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq1_4_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong1_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa1_4_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'user_id', 'DMSH0004',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt1_4')::bigint,
        'last_attempt_id', (v_ids->>'attempt1_4')::bigint,
        'best_score_percent', 50,
        'last_score_percent', 50,
        'ever_passed', false,
        'last_passed', false,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt1_4')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 2,
        'earned_score', 2,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quizzes', jsonb_build_object(
        'course_id', (v_ids->>'course2')::bigint,
        'title', '[DEMO] Kiểm tra Quản lý thông tin khách hàng CIF',
        'description', 'Câu hỏi tổng hợp phục vụ minh họa giao diện.',
        'quiz_type', 'FINAL_EXAM',
        'status', 'PUBLISHED',
        'selection_mode', 'FIXED',
        'question_count', 4,
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'published_at', v_now - interval '12 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('quiz2', v_row->'quiz_id');
    v_row := pg_temp.showcase_put('course_assessments', jsonb_build_object(
        'revision_id', (v_ids->>'revision2')::bigint,
        'assessment_revision_id', (v_ids->>'quiz2')::bigint,
        'placement', 'course',
        'is_required', false,
        'pass_score_percent', 75,
        'allow_retake', true,
        'max_attempts', 3,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assessmentlink2', v_row->'link_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q2-1',
        'course_id', (v_ids->>'course2')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q2_1', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q2_1')::bigint,
        'content', 'Phạm vi và quyền xử lý',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct2_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q2_1')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong2_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'question_id', (v_ids->>'q2_1')::bigint,
        'sort_no', 1,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq2_1', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q2-2',
        'course_id', (v_ids->>'course2')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Thông tin nào cần lưu để đối soát kết quả?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q2_2', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q2_2')::bigint,
        'content', 'Thông tin đầu vào và bằng chứng kiểm tra',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct2_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q2_2')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong2_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'question_id', (v_ids->>'q2_2')::bigint,
        'sort_no', 2,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq2_2', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q2-3',
        'course_id', (v_ids->>'course2')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q2_3', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q2_3')::bigint,
        'content', 'Ghi nhận và chuyển người phụ trách',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct2_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q2_3')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong2_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'question_id', (v_ids->>'q2_3')::bigint,
        'sort_no', 3,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq2_3', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q2-4',
        'course_id', (v_ids->>'course2')::bigint,
        'lesson_id', (v_ids->>'lesson2_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'MEDIUM',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q2_4', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q2_4')::bigint,
        'content', 'Chỉ cấp quyền cần thiết',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct2_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q2_4')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong2_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'question_id', (v_ids->>'q2_4')::bigint,
        'sort_no', 4,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq2_4', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment2_2')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 4,
        'raw_score', 4,
        'score_percent', 100,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt2_2', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_2')::bigint,
        'question_id', (v_ids->>'q2_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_2_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_2_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct2_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_2_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_2')::bigint,
        'question_id', (v_ids->>'q2_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_2_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_2_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct2_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_2_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_2')::bigint,
        'question_id', (v_ids->>'q2_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_2_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_2_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct2_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_2_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_2')::bigint,
        'question_id', (v_ids->>'q2_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_2_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_2_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct2_4')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_2_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'user_id', 'DMSH0002',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt2_2')::bigint,
        'last_attempt_id', (v_ids->>'attempt2_2')::bigint,
        'best_score_percent', 100,
        'last_score_percent', 100,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_2')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 4,
        'earned_score', 4,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment2_3')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 3,
        'raw_score', 3,
        'score_percent', 75,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt2_3', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_3')::bigint,
        'question_id', (v_ids->>'q2_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_3_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_3_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct2_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_3_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_3')::bigint,
        'question_id', (v_ids->>'q2_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_3_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_3_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct2_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_3_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_3')::bigint,
        'question_id', (v_ids->>'q2_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_3_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_3_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct2_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_3_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_3')::bigint,
        'question_id', (v_ids->>'q2_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_3_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_3_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong2_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_3_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'user_id', 'DMSH0003',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt2_3')::bigint,
        'last_attempt_id', (v_ids->>'attempt2_3')::bigint,
        'best_score_percent', 75,
        'last_score_percent', 75,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_3')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 3,
        'earned_score', 3,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment2_4')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 2,
        'raw_score', 2,
        'score_percent', 50,
        'passed', false,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt2_4', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_4')::bigint,
        'question_id', (v_ids->>'q2_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_4_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_4_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct2_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_4_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_4')::bigint,
        'question_id', (v_ids->>'q2_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_4_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_4_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct2_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_4_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_4')::bigint,
        'question_id', (v_ids->>'q2_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_4_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_4_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong2_3')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_4_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_4')::bigint,
        'question_id', (v_ids->>'q2_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct2_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct2_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong2_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct2_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq2_4_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq2_4_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong2_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa2_4_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz2')::bigint,
        'user_id', 'DMSH0004',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt2_4')::bigint,
        'last_attempt_id', (v_ids->>'attempt2_4')::bigint,
        'best_score_percent', 50,
        'last_score_percent', 50,
        'ever_passed', false,
        'last_passed', false,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt2_4')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 2,
        'earned_score', 2,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quizzes', jsonb_build_object(
        'course_id', (v_ids->>'course3')::bigint,
        'title', '[DEMO] Kiểm tra Nghiệp vụ tiền gửi và lãi suất',
        'description', 'Câu hỏi tổng hợp phục vụ minh họa giao diện.',
        'quiz_type', 'FINAL_EXAM',
        'status', 'PUBLISHED',
        'selection_mode', 'FIXED',
        'question_count', 4,
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'published_at', v_now - interval '12 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('quiz3', v_row->'quiz_id');
    v_row := pg_temp.showcase_put('course_assessments', jsonb_build_object(
        'revision_id', (v_ids->>'revision3')::bigint,
        'assessment_revision_id', (v_ids->>'quiz3')::bigint,
        'placement', 'course',
        'is_required', false,
        'pass_score_percent', 75,
        'allow_retake', true,
        'max_attempts', 3,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assessmentlink3', v_row->'link_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q3-1',
        'course_id', (v_ids->>'course3')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q3_1', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q3_1')::bigint,
        'content', 'Phạm vi và quyền xử lý',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct3_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q3_1')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong3_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'question_id', (v_ids->>'q3_1')::bigint,
        'sort_no', 1,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq3_1', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q3-2',
        'course_id', (v_ids->>'course3')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Thông tin nào cần lưu để đối soát kết quả?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q3_2', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q3_2')::bigint,
        'content', 'Thông tin đầu vào và bằng chứng kiểm tra',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct3_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q3_2')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong3_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'question_id', (v_ids->>'q3_2')::bigint,
        'sort_no', 2,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq3_2', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q3-3',
        'course_id', (v_ids->>'course3')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q3_3', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q3_3')::bigint,
        'content', 'Ghi nhận và chuyển người phụ trách',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct3_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q3_3')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong3_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'question_id', (v_ids->>'q3_3')::bigint,
        'sort_no', 3,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq3_3', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q3-4',
        'course_id', (v_ids->>'course3')::bigint,
        'lesson_id', (v_ids->>'lesson3_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'MEDIUM',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q3_4', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q3_4')::bigint,
        'content', 'Chỉ cấp quyền cần thiết',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct3_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q3_4')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong3_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'question_id', (v_ids->>'q3_4')::bigint,
        'sort_no', 4,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq3_4', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment3_2')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 4,
        'raw_score', 4,
        'score_percent', 100,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt3_2', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_2')::bigint,
        'question_id', (v_ids->>'q3_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_2_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_2_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct3_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_2_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_2')::bigint,
        'question_id', (v_ids->>'q3_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_2_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_2_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct3_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_2_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_2')::bigint,
        'question_id', (v_ids->>'q3_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_2_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_2_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct3_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_2_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_2')::bigint,
        'question_id', (v_ids->>'q3_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_2_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_2_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct3_4')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_2_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'user_id', 'DMSH0002',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt3_2')::bigint,
        'last_attempt_id', (v_ids->>'attempt3_2')::bigint,
        'best_score_percent', 100,
        'last_score_percent', 100,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_2')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 4,
        'earned_score', 4,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment3_3')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 3,
        'raw_score', 3,
        'score_percent', 75,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt3_3', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_3')::bigint,
        'question_id', (v_ids->>'q3_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_3_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_3_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct3_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_3_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_3')::bigint,
        'question_id', (v_ids->>'q3_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_3_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_3_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct3_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_3_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_3')::bigint,
        'question_id', (v_ids->>'q3_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_3_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_3_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct3_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_3_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_3')::bigint,
        'question_id', (v_ids->>'q3_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_3_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_3_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong3_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_3_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'user_id', 'DMSH0003',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt3_3')::bigint,
        'last_attempt_id', (v_ids->>'attempt3_3')::bigint,
        'best_score_percent', 75,
        'last_score_percent', 75,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_3')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 3,
        'earned_score', 3,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment3_4')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 2,
        'raw_score', 2,
        'score_percent', 50,
        'passed', false,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt3_4', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_4')::bigint,
        'question_id', (v_ids->>'q3_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_4_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_4_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct3_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_4_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_4')::bigint,
        'question_id', (v_ids->>'q3_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_4_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_4_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct3_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_4_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_4')::bigint,
        'question_id', (v_ids->>'q3_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_4_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_4_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong3_3')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_4_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_4')::bigint,
        'question_id', (v_ids->>'q3_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct3_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct3_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong3_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct3_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq3_4_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq3_4_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong3_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa3_4_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz3')::bigint,
        'user_id', 'DMSH0004',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt3_4')::bigint,
        'last_attempt_id', (v_ids->>'attempt3_4')::bigint,
        'best_score_percent', 50,
        'last_score_percent', 50,
        'ever_passed', false,
        'last_passed', false,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt3_4')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 2,
        'earned_score', 2,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quizzes', jsonb_build_object(
        'course_id', (v_ids->>'course4')::bigint,
        'title', '[DEMO] Kiểm tra Thanh toán và đối soát giao dịch',
        'description', 'Câu hỏi tổng hợp phục vụ minh họa giao diện.',
        'quiz_type', 'FINAL_EXAM',
        'status', 'PUBLISHED',
        'selection_mode', 'FIXED',
        'question_count', 4,
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'published_at', v_now - interval '12 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('quiz4', v_row->'quiz_id');
    v_row := pg_temp.showcase_put('course_assessments', jsonb_build_object(
        'revision_id', (v_ids->>'revision4')::bigint,
        'assessment_revision_id', (v_ids->>'quiz4')::bigint,
        'placement', 'course',
        'is_required', false,
        'pass_score_percent', 75,
        'allow_retake', true,
        'max_attempts', 3,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assessmentlink4', v_row->'link_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q4-1',
        'course_id', (v_ids->>'course4')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q4_1', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q4_1')::bigint,
        'content', 'Phạm vi và quyền xử lý',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct4_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q4_1')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong4_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'question_id', (v_ids->>'q4_1')::bigint,
        'sort_no', 1,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq4_1', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q4-2',
        'course_id', (v_ids->>'course4')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Thông tin nào cần lưu để đối soát kết quả?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q4_2', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q4_2')::bigint,
        'content', 'Thông tin đầu vào và bằng chứng kiểm tra',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct4_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q4_2')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong4_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'question_id', (v_ids->>'q4_2')::bigint,
        'sort_no', 2,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq4_2', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q4-3',
        'course_id', (v_ids->>'course4')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q4_3', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q4_3')::bigint,
        'content', 'Ghi nhận và chuyển người phụ trách',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct4_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q4_3')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong4_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'question_id', (v_ids->>'q4_3')::bigint,
        'sort_no', 3,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq4_3', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q4-4',
        'course_id', (v_ids->>'course4')::bigint,
        'lesson_id', (v_ids->>'lesson4_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'MEDIUM',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q4_4', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q4_4')::bigint,
        'content', 'Chỉ cấp quyền cần thiết',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct4_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q4_4')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong4_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'question_id', (v_ids->>'q4_4')::bigint,
        'sort_no', 4,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq4_4', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment4_2')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 4,
        'raw_score', 4,
        'score_percent', 100,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt4_2', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_2')::bigint,
        'question_id', (v_ids->>'q4_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_2_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_2_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct4_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_2_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_2')::bigint,
        'question_id', (v_ids->>'q4_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_2_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_2_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct4_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_2_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_2')::bigint,
        'question_id', (v_ids->>'q4_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_2_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_2_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct4_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_2_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_2')::bigint,
        'question_id', (v_ids->>'q4_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_2_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_2_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct4_4')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_2_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'user_id', 'DMSH0002',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt4_2')::bigint,
        'last_attempt_id', (v_ids->>'attempt4_2')::bigint,
        'best_score_percent', 100,
        'last_score_percent', 100,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_2')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 4,
        'earned_score', 4,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment4_3')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 3,
        'raw_score', 3,
        'score_percent', 75,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt4_3', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_3')::bigint,
        'question_id', (v_ids->>'q4_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_3_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_3_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct4_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_3_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_3')::bigint,
        'question_id', (v_ids->>'q4_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_3_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_3_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct4_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_3_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_3')::bigint,
        'question_id', (v_ids->>'q4_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_3_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_3_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct4_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_3_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_3')::bigint,
        'question_id', (v_ids->>'q4_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_3_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_3_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong4_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_3_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'user_id', 'DMSH0003',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt4_3')::bigint,
        'last_attempt_id', (v_ids->>'attempt4_3')::bigint,
        'best_score_percent', 75,
        'last_score_percent', 75,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_3')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 3,
        'earned_score', 3,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment4_4')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 2,
        'raw_score', 2,
        'score_percent', 50,
        'passed', false,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt4_4', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_4')::bigint,
        'question_id', (v_ids->>'q4_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_4_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_4_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct4_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_4_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_4')::bigint,
        'question_id', (v_ids->>'q4_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_4_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_4_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct4_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_4_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_4')::bigint,
        'question_id', (v_ids->>'q4_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_4_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_4_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong4_3')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_4_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_4')::bigint,
        'question_id', (v_ids->>'q4_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct4_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct4_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong4_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct4_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq4_4_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq4_4_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong4_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa4_4_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz4')::bigint,
        'user_id', 'DMSH0004',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt4_4')::bigint,
        'last_attempt_id', (v_ids->>'attempt4_4')::bigint,
        'best_score_percent', 50,
        'last_score_percent', 50,
        'ever_passed', false,
        'last_passed', false,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt4_4')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 2,
        'earned_score', 2,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quizzes', jsonb_build_object(
        'course_id', (v_ids->>'course5')::bigint,
        'title', '[DEMO] Kiểm tra Vận hành cuối ngày EOD/COB',
        'description', 'Câu hỏi tổng hợp phục vụ minh họa giao diện.',
        'quiz_type', 'FINAL_EXAM',
        'status', 'PUBLISHED',
        'selection_mode', 'FIXED',
        'question_count', 4,
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'published_at', v_now - interval '12 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('quiz5', v_row->'quiz_id');
    v_row := pg_temp.showcase_put('course_assessments', jsonb_build_object(
        'revision_id', (v_ids->>'revision5')::bigint,
        'assessment_revision_id', (v_ids->>'quiz5')::bigint,
        'placement', 'course',
        'is_required', false,
        'pass_score_percent', 75,
        'allow_retake', true,
        'max_attempts', 3,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assessmentlink5', v_row->'link_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q5-1',
        'course_id', (v_ids->>'course5')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q5_1', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q5_1')::bigint,
        'content', 'Phạm vi và quyền xử lý',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct5_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q5_1')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong5_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'question_id', (v_ids->>'q5_1')::bigint,
        'sort_no', 1,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq5_1', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q5-2',
        'course_id', (v_ids->>'course5')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Thông tin nào cần lưu để đối soát kết quả?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q5_2', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q5_2')::bigint,
        'content', 'Thông tin đầu vào và bằng chứng kiểm tra',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct5_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q5_2')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong5_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'question_id', (v_ids->>'q5_2')::bigint,
        'sort_no', 2,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq5_2', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q5-3',
        'course_id', (v_ids->>'course5')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q5_3', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q5_3')::bigint,
        'content', 'Ghi nhận và chuyển người phụ trách',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct5_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q5_3')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong5_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'question_id', (v_ids->>'q5_3')::bigint,
        'sort_no', 3,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq5_3', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q5-4',
        'course_id', (v_ids->>'course5')::bigint,
        'lesson_id', (v_ids->>'lesson5_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'MEDIUM',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q5_4', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q5_4')::bigint,
        'content', 'Chỉ cấp quyền cần thiết',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct5_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q5_4')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong5_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'question_id', (v_ids->>'q5_4')::bigint,
        'sort_no', 4,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq5_4', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment5_2')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 4,
        'raw_score', 4,
        'score_percent', 100,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt5_2', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_2')::bigint,
        'question_id', (v_ids->>'q5_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_2_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_2_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct5_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_2_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_2')::bigint,
        'question_id', (v_ids->>'q5_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_2_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_2_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct5_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_2_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_2')::bigint,
        'question_id', (v_ids->>'q5_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_2_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_2_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct5_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_2_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_2')::bigint,
        'question_id', (v_ids->>'q5_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_2_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_2_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct5_4')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_2_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'user_id', 'DMSH0002',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt5_2')::bigint,
        'last_attempt_id', (v_ids->>'attempt5_2')::bigint,
        'best_score_percent', 100,
        'last_score_percent', 100,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_2')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 4,
        'earned_score', 4,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment5_3')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 3,
        'raw_score', 3,
        'score_percent', 75,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt5_3', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_3')::bigint,
        'question_id', (v_ids->>'q5_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_3_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_3_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct5_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_3_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_3')::bigint,
        'question_id', (v_ids->>'q5_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_3_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_3_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct5_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_3_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_3')::bigint,
        'question_id', (v_ids->>'q5_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_3_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_3_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct5_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_3_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_3')::bigint,
        'question_id', (v_ids->>'q5_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_3_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_3_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong5_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_3_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'user_id', 'DMSH0003',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt5_3')::bigint,
        'last_attempt_id', (v_ids->>'attempt5_3')::bigint,
        'best_score_percent', 75,
        'last_score_percent', 75,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_3')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 3,
        'earned_score', 3,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment5_4')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 2,
        'raw_score', 2,
        'score_percent', 50,
        'passed', false,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt5_4', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_4')::bigint,
        'question_id', (v_ids->>'q5_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_4_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_4_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct5_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_4_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_4')::bigint,
        'question_id', (v_ids->>'q5_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_4_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_4_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct5_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_4_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_4')::bigint,
        'question_id', (v_ids->>'q5_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_4_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_4_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong5_3')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_4_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_4')::bigint,
        'question_id', (v_ids->>'q5_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct5_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct5_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong5_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct5_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq5_4_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq5_4_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong5_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa5_4_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz5')::bigint,
        'user_id', 'DMSH0004',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt5_4')::bigint,
        'last_attempt_id', (v_ids->>'attempt5_4')::bigint,
        'best_score_percent', 50,
        'last_score_percent', 50,
        'ever_passed', false,
        'last_passed', false,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt5_4')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 2,
        'earned_score', 2,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quizzes', jsonb_build_object(
        'course_id', (v_ids->>'course6')::bigint,
        'title', '[DEMO] Kiểm tra Quản lý thay đổi hệ thống',
        'description', 'Câu hỏi tổng hợp phục vụ minh họa giao diện.',
        'quiz_type', 'FINAL_EXAM',
        'status', 'PUBLISHED',
        'selection_mode', 'FIXED',
        'question_count', 4,
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'published_at', v_now - interval '12 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('quiz6', v_row->'quiz_id');
    v_row := pg_temp.showcase_put('course_assessments', jsonb_build_object(
        'revision_id', (v_ids->>'revision6')::bigint,
        'assessment_revision_id', (v_ids->>'quiz6')::bigint,
        'placement', 'course',
        'is_required', false,
        'pass_score_percent', 75,
        'allow_retake', true,
        'max_attempts', 3,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assessmentlink6', v_row->'link_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q6-1',
        'course_id', (v_ids->>'course6')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q6_1', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q6_1')::bigint,
        'content', 'Phạm vi và quyền xử lý',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct6_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q6_1')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong6_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'question_id', (v_ids->>'q6_1')::bigint,
        'sort_no', 1,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq6_1', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q6-2',
        'course_id', (v_ids->>'course6')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Thông tin nào cần lưu để đối soát kết quả?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q6_2', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q6_2')::bigint,
        'content', 'Thông tin đầu vào và bằng chứng kiểm tra',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct6_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q6_2')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong6_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'question_id', (v_ids->>'q6_2')::bigint,
        'sort_no', 2,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq6_2', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q6-3',
        'course_id', (v_ids->>'course6')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q6_3', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q6_3')::bigint,
        'content', 'Ghi nhận và chuyển người phụ trách',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct6_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q6_3')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong6_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'question_id', (v_ids->>'q6_3')::bigint,
        'sort_no', 3,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq6_3', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q6-4',
        'course_id', (v_ids->>'course6')::bigint,
        'lesson_id', (v_ids->>'lesson6_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'MEDIUM',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q6_4', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q6_4')::bigint,
        'content', 'Chỉ cấp quyền cần thiết',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct6_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q6_4')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong6_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'question_id', (v_ids->>'q6_4')::bigint,
        'sort_no', 4,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq6_4', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment6_2')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 4,
        'raw_score', 4,
        'score_percent', 100,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt6_2', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_2')::bigint,
        'question_id', (v_ids->>'q6_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_2_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_2_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct6_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_2_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_2')::bigint,
        'question_id', (v_ids->>'q6_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_2_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_2_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct6_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_2_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_2')::bigint,
        'question_id', (v_ids->>'q6_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_2_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_2_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct6_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_2_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_2')::bigint,
        'question_id', (v_ids->>'q6_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_2_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_2_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct6_4')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_2_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'user_id', 'DMSH0002',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt6_2')::bigint,
        'last_attempt_id', (v_ids->>'attempt6_2')::bigint,
        'best_score_percent', 100,
        'last_score_percent', 100,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_2')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 4,
        'earned_score', 4,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment6_3')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 3,
        'raw_score', 3,
        'score_percent', 75,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt6_3', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_3')::bigint,
        'question_id', (v_ids->>'q6_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_3_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_3_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct6_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_3_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_3')::bigint,
        'question_id', (v_ids->>'q6_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_3_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_3_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct6_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_3_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_3')::bigint,
        'question_id', (v_ids->>'q6_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_3_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_3_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct6_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_3_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_3')::bigint,
        'question_id', (v_ids->>'q6_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_3_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_3_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong6_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_3_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'user_id', 'DMSH0003',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt6_3')::bigint,
        'last_attempt_id', (v_ids->>'attempt6_3')::bigint,
        'best_score_percent', 75,
        'last_score_percent', 75,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_3')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 3,
        'earned_score', 3,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment6_4')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 2,
        'raw_score', 2,
        'score_percent', 50,
        'passed', false,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt6_4', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_4')::bigint,
        'question_id', (v_ids->>'q6_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_4_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_4_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct6_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_4_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_4')::bigint,
        'question_id', (v_ids->>'q6_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_4_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_4_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct6_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_4_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_4')::bigint,
        'question_id', (v_ids->>'q6_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_4_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_4_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong6_3')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_4_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_4')::bigint,
        'question_id', (v_ids->>'q6_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct6_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct6_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong6_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct6_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq6_4_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq6_4_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong6_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa6_4_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz6')::bigint,
        'user_id', 'DMSH0004',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt6_4')::bigint,
        'last_attempt_id', (v_ids->>'attempt6_4')::bigint,
        'best_score_percent', 50,
        'last_score_percent', 50,
        'ever_passed', false,
        'last_passed', false,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt6_4')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 2,
        'earned_score', 2,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quizzes', jsonb_build_object(
        'course_id', (v_ids->>'course7')::bigint,
        'title', '[DEMO] Kiểm tra Phân quyền và an toàn thông tin',
        'description', 'Câu hỏi tổng hợp phục vụ minh họa giao diện.',
        'quiz_type', 'FINAL_EXAM',
        'status', 'PUBLISHED',
        'selection_mode', 'FIXED',
        'question_count', 4,
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'published_at', v_now - interval '12 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('quiz7', v_row->'quiz_id');
    v_row := pg_temp.showcase_put('course_assessments', jsonb_build_object(
        'revision_id', (v_ids->>'revision7')::bigint,
        'assessment_revision_id', (v_ids->>'quiz7')::bigint,
        'placement', 'course',
        'is_required', false,
        'pass_score_percent', 75,
        'allow_retake', true,
        'max_attempts', 3,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assessmentlink7', v_row->'link_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q7-1',
        'course_id', (v_ids->>'course7')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q7_1', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q7_1')::bigint,
        'content', 'Phạm vi và quyền xử lý',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct7_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q7_1')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong7_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'question_id', (v_ids->>'q7_1')::bigint,
        'sort_no', 1,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq7_1', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q7-2',
        'course_id', (v_ids->>'course7')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Thông tin nào cần lưu để đối soát kết quả?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q7_2', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q7_2')::bigint,
        'content', 'Thông tin đầu vào và bằng chứng kiểm tra',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct7_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q7_2')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong7_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'question_id', (v_ids->>'q7_2')::bigint,
        'sort_no', 2,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq7_2', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q7-3',
        'course_id', (v_ids->>'course7')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q7_3', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q7_3')::bigint,
        'content', 'Ghi nhận và chuyển người phụ trách',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct7_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q7_3')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong7_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'question_id', (v_ids->>'q7_3')::bigint,
        'sort_no', 3,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq7_3', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q7-4',
        'course_id', (v_ids->>'course7')::bigint,
        'lesson_id', (v_ids->>'lesson7_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'MEDIUM',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q7_4', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q7_4')::bigint,
        'content', 'Chỉ cấp quyền cần thiết',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct7_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q7_4')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong7_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'question_id', (v_ids->>'q7_4')::bigint,
        'sort_no', 4,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq7_4', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment7_2')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 4,
        'raw_score', 4,
        'score_percent', 100,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt7_2', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_2')::bigint,
        'question_id', (v_ids->>'q7_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_2_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_2_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct7_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_2_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_2')::bigint,
        'question_id', (v_ids->>'q7_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_2_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_2_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct7_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_2_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_2')::bigint,
        'question_id', (v_ids->>'q7_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_2_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_2_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct7_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_2_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_2')::bigint,
        'question_id', (v_ids->>'q7_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_2_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_2_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct7_4')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_2_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'user_id', 'DMSH0002',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt7_2')::bigint,
        'last_attempt_id', (v_ids->>'attempt7_2')::bigint,
        'best_score_percent', 100,
        'last_score_percent', 100,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_2')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 4,
        'earned_score', 4,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment7_3')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 3,
        'raw_score', 3,
        'score_percent', 75,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt7_3', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_3')::bigint,
        'question_id', (v_ids->>'q7_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_3_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_3_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct7_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_3_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_3')::bigint,
        'question_id', (v_ids->>'q7_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_3_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_3_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct7_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_3_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_3')::bigint,
        'question_id', (v_ids->>'q7_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_3_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_3_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct7_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_3_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_3')::bigint,
        'question_id', (v_ids->>'q7_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_3_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_3_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong7_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_3_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'user_id', 'DMSH0003',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt7_3')::bigint,
        'last_attempt_id', (v_ids->>'attempt7_3')::bigint,
        'best_score_percent', 75,
        'last_score_percent', 75,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_3')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 3,
        'earned_score', 3,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment7_4')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 2,
        'raw_score', 2,
        'score_percent', 50,
        'passed', false,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt7_4', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_4')::bigint,
        'question_id', (v_ids->>'q7_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_4_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_4_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct7_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_4_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_4')::bigint,
        'question_id', (v_ids->>'q7_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_4_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_4_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct7_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_4_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_4')::bigint,
        'question_id', (v_ids->>'q7_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_4_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_4_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong7_3')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_4_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_4')::bigint,
        'question_id', (v_ids->>'q7_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct7_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct7_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong7_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct7_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq7_4_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq7_4_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong7_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa7_4_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz7')::bigint,
        'user_id', 'DMSH0004',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt7_4')::bigint,
        'last_attempt_id', (v_ids->>'attempt7_4')::bigint,
        'best_score_percent', 50,
        'last_score_percent', 50,
        'ever_passed', false,
        'last_passed', false,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt7_4')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 2,
        'earned_score', 2,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quizzes', jsonb_build_object(
        'course_id', (v_ids->>'course8')::bigint,
        'title', '[DEMO] Kiểm tra Giám sát và xử lý sự cố',
        'description', 'Câu hỏi tổng hợp phục vụ minh họa giao diện.',
        'quiz_type', 'FINAL_EXAM',
        'status', 'PUBLISHED',
        'selection_mode', 'FIXED',
        'question_count', 4,
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'published_at', v_now - interval '12 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('quiz8', v_row->'quiz_id');
    v_row := pg_temp.showcase_put('course_assessments', jsonb_build_object(
        'revision_id', (v_ids->>'revision8')::bigint,
        'assessment_revision_id', (v_ids->>'quiz8')::bigint,
        'placement', 'course',
        'is_required', false,
        'pass_score_percent', 75,
        'allow_retake', true,
        'max_attempts', 3,
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('assessmentlink8', v_row->'link_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q8-1',
        'course_id', (v_ids->>'course8')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q8_1', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q8_1')::bigint,
        'content', 'Phạm vi và quyền xử lý',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct8_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q8_1')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong8_1', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'question_id', (v_ids->>'q8_1')::bigint,
        'sort_no', 1,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq8_1', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q8-2',
        'course_id', (v_ids->>'course8')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Thông tin nào cần lưu để đối soát kết quả?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q8_2', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q8_2')::bigint,
        'content', 'Thông tin đầu vào và bằng chứng kiểm tra',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct8_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q8_2')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong8_2', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'question_id', (v_ids->>'q8_2')::bigint,
        'sort_no', 2,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq8_2', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q8-3',
        'course_id', (v_ids->>'course8')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'EASY',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q8_3', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q8_3')::bigint,
        'content', 'Ghi nhận và chuyển người phụ trách',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct8_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q8_3')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong8_3', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'question_id', (v_ids->>'q8_3')::bigint,
        'sort_no', 3,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq8_3', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('questions', jsonb_build_object(
        'code', 'DEMO-SHOWCASE-Q8-4',
        'course_id', (v_ids->>'course8')::bigint,
        'lesson_id', (v_ids->>'lesson8_1')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_type', 'SINGLE',
        'content', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'explanation', 'Xác nhận phạm vi, giữ bằng chứng và chuyển đúng người phụ trách; đây là ví dụ đào tạo tổng hợp.',
        'difficulty', 'MEDIUM',
        'points', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('q8_4', v_row->'question_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q8_4')::bigint,
        'content', 'Chỉ cấp quyền cần thiết',
        'is_correct', true,
        'sort_no', 1,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('correct8_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('question_options', jsonb_build_object(
        'question_id', (v_ids->>'q8_4')::bigint,
        'content', 'Bỏ qua kiểm tra và xử lý ngay',
        'is_correct', false,
        'sort_no', 2,
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wrong8_4', v_row->'option_id');
    v_row := pg_temp.showcase_put('quiz_questions', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'question_id', (v_ids->>'q8_4')::bigint,
        'sort_no', 4,
        'points', 1,
        'created_by', 'DMSH0001',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qq8_4', v_row->'quiz_question_id');
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'user_id', 'DMSH0002',
        'enrollment_id', (v_ids->>'enrollment8_2')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 4,
        'raw_score', 4,
        'score_percent', 100,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt8_2', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_2')::bigint,
        'question_id', (v_ids->>'q8_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_2_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_2_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct8_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_2_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_2')::bigint,
        'question_id', (v_ids->>'q8_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_2_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_2_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct8_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_2_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_2')::bigint,
        'question_id', (v_ids->>'q8_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_2_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_2_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct8_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_2_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_2')::bigint,
        'question_id', (v_ids->>'q8_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_2_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_2_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct8_4')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_2_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'user_id', 'DMSH0002',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt8_2')::bigint,
        'last_attempt_id', (v_ids->>'attempt8_2')::bigint,
        'best_score_percent', 100,
        'last_score_percent', 100,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_2')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 4,
        'earned_score', 4,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'user_id', 'DMSH0003',
        'enrollment_id', (v_ids->>'enrollment8_3')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 3,
        'raw_score', 3,
        'score_percent', 75,
        'passed', true,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt8_3', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_3')::bigint,
        'question_id', (v_ids->>'q8_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_3_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_3_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct8_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_3_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_3')::bigint,
        'question_id', (v_ids->>'q8_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_3_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_3_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct8_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_3_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_3')::bigint,
        'question_id', (v_ids->>'q8_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_3_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_3_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct8_3')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_3_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_3')::bigint,
        'question_id', (v_ids->>'q8_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_3_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_3_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong8_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_3_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'user_id', 'DMSH0003',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt8_3')::bigint,
        'last_attempt_id', (v_ids->>'attempt8_3')::bigint,
        'best_score_percent', 75,
        'last_score_percent', 75,
        'ever_passed', true,
        'last_passed', true,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_3')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 3,
        'earned_score', 3,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempts', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'user_id', 'DMSH0004',
        'enrollment_id', (v_ids->>'enrollment8_4')::bigint,
        'attempt_no', 1,
        'status', 'SUBMITTED',
        'submit_type', 'MANUAL',
        'started_at', v_now - interval '2 days',
        'deadline_at', v_now - interval '2 days' + interval '15 minutes',
        'submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'last_saved_at', v_now - interval '2 days' + interval '8 minutes',
        'time_limit_minutes', 15,
        'pass_percent', 75,
        'scoring_mode', 'ALL_OR_NOTHING',
        'grace_seconds', 5,
        'total_questions', 4,
        'max_score', 4,
        'correct_count', 2,
        'raw_score', 2,
        'score_percent', 50,
        'passed', false,
        'graded_at', v_now - interval '2 days' + interval '8 minutes',
        'org_id', (v_ids->>'org')::bigint,
        'team_id', (v_ids->>'team')::bigint,
        'user_agent', 'DEMO_SHOWCASE_SYNTHETIC',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('attempt8_4', v_row->'attempt_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_4')::bigint,
        'question_id', (v_ids->>'q8_1')::bigint,
        'question_version', 1,
        'display_order', 1,
        'question_type', 'SINGLE',
        'content_snapshot', 'Trước khi xử lý một yêu cầu, cần kiểm tra điều gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_1')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_1')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_1')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_1')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_4_1', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_4_1')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct8_1')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_4_1', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_4')::bigint,
        'question_id', (v_ids->>'q8_2')::bigint,
        'question_version', 1,
        'display_order', 2,
        'question_type', 'SINGLE',
        'content_snapshot', 'Thông tin nào cần lưu để đối soát kết quả?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_2')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_2')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_2')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_2')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_4_2', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_4_2')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'correct8_2')::bigint),
        'is_correct', true,
        'score_awarded', 1,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_4_2', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_4')::bigint,
        'question_id', (v_ids->>'q8_3')::bigint,
        'question_version', 1,
        'display_order', 3,
        'question_type', 'SINGLE',
        'content_snapshot', 'Khi chưa đủ bằng chứng, bước tiếp theo phù hợp là gì?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_3')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_3')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_3')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_3')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'EASY',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_4_3', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_4_3')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong8_3')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_4_3', v_row->'answer_id');
    v_row := pg_temp.showcase_put('attempt_questions', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_4')::bigint,
        'question_id', (v_ids->>'q8_4')::bigint,
        'question_version', 1,
        'display_order', 4,
        'question_type', 'SINGLE',
        'content_snapshot', 'Nguyên tắc nào giúp kiểm soát truy cập?',
        'options_snapshot', jsonb_build_array(jsonb_build_object('id',(v_ids->>'correct8_4')::bigint,'content',(SELECT content FROM learning_hub.question_options WHERE option_id=(v_ids->>'correct8_4')::bigint)), jsonb_build_object('id',(v_ids->>'wrong8_4')::bigint,'content','Bỏ qua kiểm tra và xử lý ngay')),
        'correct_option_ids', jsonb_build_array((v_ids->>'correct8_4')::bigint),
        'explanation_snapshot', 'Tình huống tổng hợp: kiểm tra phạm vi và bằng chứng trước khi xử lý.',
        'points', 1,
        'competency_id', (v_ids->>'competency')::bigint,
        'difficulty', 'MEDIUM',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aq8_4_4', v_row->'attempt_question_id');
    v_row := pg_temp.showcase_put('attempt_answers', jsonb_build_object(
        'attempt_question_id', (v_ids->>'aq8_4_4')::bigint,
        'selected_option_ids', jsonb_build_array((v_ids->>'wrong8_4')::bigint),
        'is_correct', false,
        'score_awarded', 0,
        'answered_at', v_now - interval '2 days' + interval '7 minutes'
    ));
    v_ids := v_ids || jsonb_build_object('aa8_4_4', v_row->'answer_id');
    v_row := pg_temp.showcase_put('quiz_user_results', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz8')::bigint,
        'user_id', 'DMSH0004',
        'graded_attempt_count', 1,
        'best_attempt_id', (v_ids->>'attempt8_4')::bigint,
        'last_attempt_id', (v_ids->>'attempt8_4')::bigint,
        'best_score_percent', 50,
        'last_score_percent', 50,
        'ever_passed', false,
        'last_passed', false,
        'last_submitted_at', v_now - interval '2 days' + interval '8 minutes',
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('attempt_competency_scores', jsonb_build_object(
        'attempt_id', (v_ids->>'attempt8_4')::bigint,
        'competency_id', (v_ids->>'competency')::bigint,
        'question_count', 4,
        'correct_count', 2,
        'earned_score', 2,
        'max_score', 4,
        'update_time', v_now - interval '10 days'
    ));
    v_row := pg_temp.showcase_put('quiz_attempt_grants', jsonb_build_object(
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'user_id', 'DMSH0004',
        'extra_attempts', 1,
        'reason', '[DEMO] Minh họa lượt thi bổ sung.',
        'granted_by', 'DMSH0001'
    ));
    v_ids := v_ids || jsonb_build_object('quizgrant', v_row->'grant_id');
    v_row := pg_temp.showcase_put('attempt_regrades', jsonb_build_object(
        'batch_id', 'demo-showcase-v1-regrade',
        'quiz_id', (v_ids->>'quiz1')::bigint,
        'attempt_id', (v_ids->>'attempt1_2')::bigint,
        'question_id', (v_ids->>'q1_1')::bigint,
        'reason', '[DEMO] Đối soát mẫu không thay đổi đáp án và kết quả.',
        'old_correct_option_ids', jsonb_build_array((v_ids->>'correct1_1')::bigint),
        'new_correct_option_ids', jsonb_build_array((v_ids->>'correct1_1')::bigint),
        'old_correct_count', 4,
        'new_correct_count', 4,
        'old_raw_score', 4,
        'new_raw_score', 4,
        'old_score_percent', 100,
        'new_score_percent', 100,
        'old_passed', true,
        'new_passed', true,
        'regraded_by', 'DMSH0001',
        'regraded_at', v_now - interval '1 days'
    ));
    v_ids := v_ids || jsonb_build_object('regrade', v_row->'regrade_id');

    -- Kho tri thức: mẫu lỗi có kiểm soát, không giả tệp hoặc embedding dùng cho truy hồi
    v_row := pg_temp.showcase_put('knowledge_documents', jsonb_build_object(
        'uploaded_by', 'DMSH0001',
        'file_name', 'DEMO-checklist-van-hanh.md',
        'mime_type', 'text/markdown',
        'size_bytes', 0,
        'storage_key', 'demo-showcase-v1-no-file.md',
        'status', 'failed',
        'error_message', '[DEMO] Không có tệp thật; bản ghi chỉ minh họa trạng thái xử lý lỗi.',
        'chunk_count', 1,
        'attempts', 1,
        'processed_at', v_now - interval '1 days',
        'create_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('document', v_row->'document_id');
    v_row := pg_temp.showcase_put('knowledge_document_chunks', jsonb_build_object(
        'document_id', (v_ids->>'document')::bigint,
        'chunk_index', 0,
        'content', '[DEMO] Đoạn tổng hợp không tham gia truy hồi vì tài liệu không ở trạng thái ready.',
        'line_start', 1,
        'line_end', 1,
        'token_estimate', 30,
        'embedding', ('[' || array_to_string(array_fill(0, ARRAY[1024]), ',') || ']')
    ));
    v_ids := v_ids || jsonb_build_object('documentchunk', v_row->'chunk_id');

    -- Community: nội dung, Guild, hỏi đáp, review, bộ sưu tập và kiểm duyệt
    v_row := pg_temp.showcase_put('community_guilds', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'slug', 'demo-showcase-v1-guild-0',
        'name', '[DEMO] Vận hành Core Banking',
        'description', 'Không gian chia sẻ với dữ liệu tổng hợp.',
        'owner_user_id', 'DMSH0001',
        'visibility', 'organization',
        'join_policy', 'approval',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('guild0', v_row->'guild_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild0')::bigint,
        'user_id', 'DMSH0001',
        'member_role', 'owner',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember0_1', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild0')::bigint,
        'user_id', 'DMSH0002',
        'member_role', 'member',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember0_2', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild0')::bigint,
        'user_id', 'DMSH0003',
        'member_role', 'member',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember0_3', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild0')::bigint,
        'user_id', 'DMSH0004',
        'member_role', 'member',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember0_4', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guilds', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'slug', 'demo-showcase-v1-guild-1',
        'name', '[DEMO] Thiết kế API và tích hợp',
        'description', 'Không gian chia sẻ với dữ liệu tổng hợp.',
        'owner_user_id', 'DMSH0001',
        'visibility', 'organization',
        'join_policy', 'approval',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('guild1', v_row->'guild_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild1')::bigint,
        'user_id', 'DMSH0001',
        'member_role', 'owner',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember1_1', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild1')::bigint,
        'user_id', 'DMSH0002',
        'member_role', 'member',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember1_2', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild1')::bigint,
        'user_id', 'DMSH0003',
        'member_role', 'member',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember1_3', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild1')::bigint,
        'user_id', 'DMSH0004',
        'member_role', 'member',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember1_4', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guilds', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'slug', 'demo-showcase-v1-guild-2',
        'name', '[DEMO] Chia sẻ kinh nghiệm nghiệp vụ',
        'description', 'Không gian chia sẻ với dữ liệu tổng hợp.',
        'owner_user_id', 'DMSH0001',
        'visibility', 'organization',
        'join_policy', 'approval',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('guild2', v_row->'guild_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild2')::bigint,
        'user_id', 'DMSH0001',
        'member_role', 'owner',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember2_1', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild2')::bigint,
        'user_id', 'DMSH0002',
        'member_role', 'member',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember2_2', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild2')::bigint,
        'user_id', 'DMSH0003',
        'member_role', 'member',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember2_3', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_guild_members', jsonb_build_object(
        'guild_id', (v_ids->>'guild2')::bigint,
        'user_id', 'DMSH0004',
        'member_role', 'member',
        'status', 'active',
        'joined_at', v_now - interval '15 days',
        'approved_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('guildmember2_4', v_row->'membership_id');
    v_row := pg_temp.showcase_put('community_tags', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'slug', 'demo-showcase-v1-tag-0',
        'name', '[DEMO] Vận hành',
        'description', 'Thẻ của bộ dữ liệu minh họa.',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('tag0', v_row->'tag_id');
    v_row := pg_temp.showcase_put('community_tag_aliases', jsonb_build_object(
        'tag_id', (v_ids->>'tag0')::bigint,
        'normalized_alias', 'demo-showcase-v1-alias-0',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('alias0', v_row->'alias_id');
    v_row := pg_temp.showcase_put('community_tags', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'slug', 'demo-showcase-v1-tag-1',
        'name', '[DEMO] Tích hợp API',
        'description', 'Thẻ của bộ dữ liệu minh họa.',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('tag1', v_row->'tag_id');
    v_row := pg_temp.showcase_put('community_tag_aliases', jsonb_build_object(
        'tag_id', (v_ids->>'tag1')::bigint,
        'normalized_alias', 'demo-showcase-v1-alias-1',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('alias1', v_row->'alias_id');
    v_row := pg_temp.showcase_put('community_tags', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'slug', 'demo-showcase-v1-tag-2',
        'name', '[DEMO] Kiểm soát',
        'description', 'Thẻ của bộ dữ liệu minh họa.',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('tag2', v_row->'tag_id');
    v_row := pg_temp.showcase_put('community_tag_aliases', jsonb_build_object(
        'tag_id', (v_ids->>'tag2')::bigint,
        'normalized_alias', 'demo-showcase-v1-alias-2',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('alias2', v_row->'alias_id');
    v_row := pg_temp.showcase_put('community_tags', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'slug', 'demo-showcase-v1-tag-3',
        'name', '[DEMO] Tiền gửi',
        'description', 'Thẻ của bộ dữ liệu minh họa.',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('tag3', v_row->'tag_id');
    v_row := pg_temp.showcase_put('community_tag_aliases', jsonb_build_object(
        'tag_id', (v_ids->>'tag3')::bigint,
        'normalized_alias', 'demo-showcase-v1-alias-3',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('alias3', v_row->'alias_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'question',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild0')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content0', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content0')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Làm gì khi tác vụ cuối ngày dừng giữa chừng?',
        'body_markdown', '## Làm gì khi tác vụ cuối ngày dừng giữa chừng?

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '1 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision0', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision0')::bigint
    WHERE content_id = (v_ids->>'content0')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content0')::bigint,
        'tag_id', (v_ids->>'tag0')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag0', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content0')::bigint,
        'guild_id', (v_ids->>'guild0')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild0', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content0')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision0')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment0', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content0')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote0', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content0')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view0', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'question',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild1')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '2 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content1', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content1')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Kiểm tra chênh lệch đối soát từ đâu?',
        'body_markdown', '## Kiểm tra chênh lệch đối soát từ đâu?

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '2 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision1', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision1')::bigint
    WHERE content_id = (v_ids->>'content1')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content1')::bigint,
        'tag_id', (v_ids->>'tag1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag1', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content1')::bigint,
        'guild_id', (v_ids->>'guild1')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild1', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content1')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision1')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment1', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content1')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote1', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content1')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view1', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'question',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild2')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '3 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content2', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content2')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Cách ghi nhận bằng chứng xử lý sự cố?',
        'body_markdown', '## Cách ghi nhận bằng chứng xử lý sự cố?

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '3 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision2', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision2')::bigint
    WHERE content_id = (v_ids->>'content2')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content2')::bigint,
        'tag_id', (v_ids->>'tag2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag2', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content2')::bigint,
        'guild_id', (v_ids->>'guild2')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild2', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content2')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision2')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment2', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content2')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote2', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content2')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view2', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'answer',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0002',
        'maintainer_user_id', 'DMSH0002',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild0')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '4 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content3', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content3')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Đối chiếu trạng thái tác vụ và nhật ký',
        'body_markdown', '## Đối chiếu trạng thái tác vụ và nhật ký

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '4 days',
        'published_by', 'DMSH0002',
        'created_by', 'DMSH0002',
        'updated_by', 'DMSH0002',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision3', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision3')::bigint
    WHERE content_id = (v_ids->>'content3')::bigint;
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content3')::bigint,
        'guild_id', (v_ids->>'guild0')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild3', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'answer',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0003',
        'maintainer_user_id', 'DMSH0003',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild1')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '5 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content4', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content4')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Bắt đầu từ phạm vi và thời điểm đối soát',
        'body_markdown', '## Bắt đầu từ phạm vi và thời điểm đối soát

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '5 days',
        'published_by', 'DMSH0003',
        'created_by', 'DMSH0003',
        'updated_by', 'DMSH0003',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision4', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision4')::bigint
    WHERE content_id = (v_ids->>'content4')::bigint;
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content4')::bigint,
        'guild_id', (v_ids->>'guild1')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild4', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'article',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild2')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '6 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content5', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content5')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Checklist chuẩn bị trước ca vận hành',
        'body_markdown', '## Checklist chuẩn bị trước ca vận hành

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '6 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision5', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision5')::bigint
    WHERE content_id = (v_ids->>'content5')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content5')::bigint,
        'tag_id', (v_ids->>'tag1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag5', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content5')::bigint,
        'guild_id', (v_ids->>'guild2')::bigint,
        'is_pinned', true,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild5', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content5')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision5')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment5', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content5')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote5', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content5')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view5', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'article',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild0')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content6', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content6')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Các lỗi thường gặp khi thiết kế API',
        'body_markdown', '## Các lỗi thường gặp khi thiết kế API

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '1 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision6', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision6')::bigint
    WHERE content_id = (v_ids->>'content6')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content6')::bigint,
        'tag_id', (v_ids->>'tag2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag6', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content6')::bigint,
        'guild_id', (v_ids->>'guild0')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild6', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content6')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision6')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment6', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content6')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote6', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content6')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view6', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'article',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild1')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '2 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content7', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content7')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Hướng dẫn đọc thông báo nghiệp vụ',
        'body_markdown', '## Hướng dẫn đọc thông báo nghiệp vụ

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '2 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision7', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision7')::bigint
    WHERE content_id = (v_ids->>'content7')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content7')::bigint,
        'tag_id', (v_ids->>'tag3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag7', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content7')::bigint,
        'guild_id', (v_ids->>'guild1')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild7', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content7')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision7')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment7', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content7')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote7', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content7')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view7', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'article',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild2')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '3 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content8', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content8')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Quản lý thay đổi cấu hình an toàn',
        'body_markdown', '## Quản lý thay đổi cấu hình an toàn

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '3 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision8', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision8')::bigint
    WHERE content_id = (v_ids->>'content8')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content8')::bigint,
        'tag_id', (v_ids->>'tag0')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag8', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content8')::bigint,
        'guild_id', (v_ids->>'guild2')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild8', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content8')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision8')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment8', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content8')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote8', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content8')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view8', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'article',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild0')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '4 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content9', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content9')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Gợi ý xây dựng tài liệu bàn giao',
        'body_markdown', '## Gợi ý xây dựng tài liệu bàn giao

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '4 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision9', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision9')::bigint
    WHERE content_id = (v_ids->>'content9')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content9')::bigint,
        'tag_id', (v_ids->>'tag1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag9', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content9')::bigint,
        'guild_id', (v_ids->>'guild0')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild9', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content9')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision9')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment9', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content9')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote9', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content9')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view9', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'wiki',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild1')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '5 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content10', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content10')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Sổ tay thuật ngữ Core Banking',
        'body_markdown', '## Sổ tay thuật ngữ Core Banking

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{"wiki":{"documentClass":"guide"}}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '5 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision10', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision10')::bigint
    WHERE content_id = (v_ids->>'content10')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content10')::bigint,
        'tag_id', (v_ids->>'tag2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag10', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content10')::bigint,
        'guild_id', (v_ids->>'guild1')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild10', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content10')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision10')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment10', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content10')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote10', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content10')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view10', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'radar',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild2')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '6 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content11', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content11')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Đánh giá công nghệ trong môi trường thử nghiệm',
        'body_markdown', '## Đánh giá công nghệ trong môi trường thử nghiệm

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{"radar":{"technologyName":"PostgreSQL","technologyVersion":"16","stage":"assess","approvalScope":"Thử nghiệm DEMO, chưa phải chuẩn áp dụng."}}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '6 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision11', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision11')::bigint
    WHERE content_id = (v_ids->>'content11')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content11')::bigint,
        'tag_id', (v_ids->>'tag3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag11', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content11')::bigint,
        'guild_id', (v_ids->>'guild2')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild11', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content11')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision11')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment11', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content11')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote11', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content11')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view11', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'architecture_review',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild0')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content12', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content12')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Review phương án lưu bằng chứng đối soát',
        'body_markdown', '## Review phương án lưu bằng chứng đối soát

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{"review":{"problem":"Kiểm soát bằng chứng khi đối soát dữ liệu tổng hợp.","constraints":"Giữ kiến trúc hiện có.","decisionsNeeded":["Cách truy vết kết quả kiểm tra"]}}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '1 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision12', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision12')::bigint
    WHERE content_id = (v_ids->>'content12')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content12')::bigint,
        'tag_id', (v_ids->>'tag0')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag12', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content12')::bigint,
        'guild_id', (v_ids->>'guild0')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild12', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content12')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision12')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment12', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content12')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote12', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content12')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view12', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'lesson_learned',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild1')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '2 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content13', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content13')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Bài học về kiểm soát thay đổi tham số',
        'body_markdown', '## Bài học về kiểm soát thay đổi tham số

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{"lessonLearned":{"occurredOn":"2026-11-01","symptoms":"Thời gian xử lý dài trong tình huống thử nghiệm.","rootCause":"Chưa có bước kiểm tra phạm vi.","resolution":"Bổ sung checklist.","prevention":"Rà soát chéo trước thay đổi."}}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '2 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision13', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision13')::bigint
    WHERE content_id = (v_ids->>'content13')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content13')::bigint,
        'tag_id', (v_ids->>'tag1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag13', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content13')::bigint,
        'guild_id', (v_ids->>'guild1')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild13', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content13')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision13')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment13', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content13')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote13', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content13')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view13', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'knowledge_request',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild2')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '3 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content14', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content14')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Cần hướng dẫn thực hành đối soát cuối ngày',
        'body_markdown', '## Cần hướng dẫn thực hành đối soát cuối ngày

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'published',
        'published_at', v_now - interval '3 days',
        'published_by', 'DMSH0001',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision14', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_published_revision_id = (v_ids->>'crevision14')::bigint
    WHERE content_id = (v_ids->>'content14')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content14')::bigint,
        'tag_id', (v_ids->>'tag2')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag14', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content14')::bigint,
        'guild_id', (v_ids->>'guild2')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild14', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_comments', jsonb_build_object(
        'content_id', (v_ids->>'content14')::bigint,
        'author_user_id', 'DMSH0002',
        'revision_id', (v_ids->>'crevision14')::bigint,
        'body_markdown', '[DEMO] Nội dung rõ ràng. Nên bổ sung ví dụ kiểm tra kết quả.',
        'status', 'visible',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('comment14', v_row->'comment_id');
    v_row := pg_temp.showcase_put('community_votes', jsonb_build_object(
        'content_id', (v_ids->>'content14')::bigint,
        'user_id', 'DMSH0003',
        'value', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('vote14', v_row->'vote_id');
    v_row := pg_temp.showcase_put('community_content_views', jsonb_build_object(
        'content_id', (v_ids->>'content14')::bigint,
        'user_id', 'DMSH0002',
        'view_date', (v_now - interval '1 day')::date,
        'first_view_at', v_now - interval '1 days',
        'last_view_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('view14', v_row->'view_id');
    v_row := pg_temp.showcase_put('community_contents', jsonb_build_object(
        'kind', 'recording',
        'owner_org_id', (v_ids->>'org')::bigint,
        'author_user_id', 'DMSH0001',
        'maintainer_user_id', 'DMSH0001',
        'visibility', 'organization',
        'primary_guild_id', (v_ids->>'guild0')::bigint,
        'lifecycle_status', 'active',
        'last_activity_at', v_now - interval '4 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('content15', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_revisions', jsonb_build_object(
        'content_id', (v_ids->>'content15')::bigint,
        'revision_no', 1,
        'title', '[DEMO] Bản nháp buổi chia sẻ kinh nghiệm vận hành',
        'body_markdown', '## Bản nháp buổi chia sẻ kinh nghiệm vận hành

Nội dung tổng hợp trong đơn vị DEMO.

- Xác nhận phạm vi và người phụ trách.
- Đối chiếu thông tin đầu vào với kết quả xử lý.
- Lưu bằng chứng kiểm tra để tiện trao đổi.

Không dùng làm tài liệu nghiệp vụ chính thức.',
        'summary', 'Ví dụ trao đổi và thực hành với dữ liệu tổng hợp.',
        'context_json', '{}'::jsonb,
        'revision_status', 'draft',
        'published_at', NULL,
        'published_by', NULL,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('crevision15', v_row->'revision_id');
    UPDATE learning_hub.community_contents SET current_draft_revision_id = (v_ids->>'crevision15')::bigint
    WHERE content_id = (v_ids->>'content15')::bigint;
    v_row := pg_temp.showcase_put('community_content_tags', jsonb_build_object(
        'content_id', (v_ids->>'content15')::bigint,
        'tag_id', (v_ids->>'tag3')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ctag15', v_row->'content_tag_id');
    v_row := pg_temp.showcase_put('community_content_guilds', jsonb_build_object(
        'content_id', (v_ids->>'content15')::bigint,
        'guild_id', (v_ids->>'guild0')::bigint,
        'is_pinned', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cguild15', v_row->'content_guild_id');
    v_row := pg_temp.showcase_put('community_questions', jsonb_build_object(
        'content_id', (v_ids->>'content0')::bigint,
        'resolution_status', 'solved',
        'accepted_answer_content_id', (v_ids->>'content3')::bigint,
        'accepted_answer_revision_id', (v_ids->>'crevision3')::bigint,
        'accepted_by', 'DMSH0001',
        'accepted_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cquestion0', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_questions', jsonb_build_object(
        'content_id', (v_ids->>'content1')::bigint,
        'resolution_status', 'answered',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cquestion1', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_questions', jsonb_build_object(
        'content_id', (v_ids->>'content2')::bigint,
        'resolution_status', 'unanswered',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cquestion2', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_answers', jsonb_build_object(
        'content_id', (v_ids->>'content3')::bigint,
        'question_content_id', (v_ids->>'content0')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('answer3', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_answers', jsonb_build_object(
        'content_id', (v_ids->>'content4')::bigint,
        'question_content_id', (v_ids->>'content1')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('answer4', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_question_links', jsonb_build_object(
        'source_question_id', (v_ids->>'content2')::bigint,
        'target_question_id', (v_ids->>'content0')::bigint,
        'relation_type', 'related',
        'reason', '[DEMO] Cùng chủ đề bằng chứng và nhật ký vận hành.',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('qlink', v_row->'question_link_id');
    v_row := pg_temp.showcase_put('community_help_requests', jsonb_build_object(
        'question_content_id', (v_ids->>'content2')::bigint,
        'requested_by', 'DMSH0001',
        'target_user_id', 'DMSH0002',
        'status', 'cancelled',
        'due_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('help', v_row->'help_request_id');
    v_row := pg_temp.showcase_put('community_wiki_pages', jsonb_build_object(
        'content_id', (v_ids->>'content10')::bigint,
        'guild_id', (v_ids->>'guild1')::bigint,
        'slug', 'demo-showcase-v1-wiki',
        'sort_no', 1,
        'review_due_at', (v_now + interval '30 days')::date,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('wiki', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_radar_entries', jsonb_build_object(
        'content_id', (v_ids->>'content11')::bigint,
        'guild_id', (v_ids->>'guild2')::bigint,
        'review_due_at', (v_now + interval '30 days')::date,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('radar', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_review_proposals', jsonb_build_object(
        'content_id', (v_ids->>'content12')::bigint,
        'review_status', 'open',
        'due_at', v_now + interval '14 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('review', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_review_invitations', jsonb_build_object(
        'proposal_content_id', (v_ids->>'content12')::bigint,
        'reviewer_user_id', 'DMSH0002',
        'invited_by', 'DMSH0001',
        'status', 'accepted',
        'responded_at', v_now - interval '1 days',
        'expires_at', v_now + interval '14 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('invitation', v_row->'invitation_id');
    v_row := pg_temp.showcase_put('community_review_threads', jsonb_build_object(
        'proposal_content_id', (v_ids->>'content12')::bigint,
        'revision_id', (v_ids->>'crevision12')::bigint,
        'anchor_json', '{}'::jsonb,
        'status', 'open',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('thread', v_row->'thread_id');
    v_row := pg_temp.showcase_put('community_knowledge_requests', jsonb_build_object(
        'content_id', (v_ids->>'content14')::bigint,
        'request_status', 'open',
        'due_at', v_now + interval '21 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('request', v_row->'content_id');
    v_row := pg_temp.showcase_put('community_content_access_grants', jsonb_build_object(
        'content_id', (v_ids->>'content5')::bigint,
        'user_id', 'DMSH0002',
        'permission', 'reader',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cgrant', v_row->'grant_id');
    v_row := pg_temp.showcase_put('community_revision_approvals', jsonb_build_object(
        'revision_id', (v_ids->>'crevision10')::bigint,
        'reviewer_user_id', 'DMSH0002',
        'decision', 'approved',
        'note', '[DEMO] Đã rà soát nội dung minh họa.',
        'decided_at', v_now - interval '2 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('approval', v_row->'approval_id');
    v_row := pg_temp.showcase_put('community_content_links', jsonb_build_object(
        'source_content_id', (v_ids->>'content5')::bigint,
        'target_content_id', (v_ids->>'content10')::bigint,
        'relation_type', 'related',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('clink', v_row->'link_id');
    v_row := pg_temp.showcase_put('community_user_expertise', jsonb_build_object(
        'user_id', 'DMSH0002',
        'tag_id', (v_ids->>'tag0')::bigint,
        'description', '[DEMO] Kinh nghiệm trao đổi về vận hành.',
        'available_for_help', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('expertise', v_row->'expertise_id');
    v_row := pg_temp.showcase_put('community_moderators', jsonb_build_object(
        'user_id', 'DMSH0004',
        'org_id', (v_ids->>'org')::bigint,
        'status', 'revoked',
        'granted_by', 'DMSH0001',
        'granted_at', v_now - interval '10 days',
        'revoked_at', v_now - interval '9 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('moderator', v_row->'moderator_id');
    v_row := pg_temp.showcase_put('community_subscriptions', jsonb_build_object(
        'user_id', 'DMSH0002',
        'guild_id', (v_ids->>'guild0')::bigint,
        'delivery_mode', 'muted',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('subscription', v_row->'subscription_id');
    v_row := pg_temp.showcase_put('community_collections', jsonb_build_object(
        'owner_user_id', 'DMSH0001',
        'owner_org_id', (v_ids->>'org')::bigint,
        'name', '[DEMO] Bắt đầu với Core Banking',
        'description', 'Bộ sưu tập nội dung tổng hợp.',
        'visibility', 'organization',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('collection1', v_row->'collection_id');
    v_row := pg_temp.showcase_put('community_collections', jsonb_build_object(
        'owner_user_id', 'DMSH0001',
        'owner_org_id', (v_ids->>'org')::bigint,
        'name', '[DEMO] Checklist vận hành',
        'description', 'Bộ sưu tập nội dung tổng hợp.',
        'visibility', 'organization',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('collection2', v_row->'collection_id');
    v_row := pg_temp.showcase_put('community_collections', jsonb_build_object(
        'owner_user_id', 'DMSH0001',
        'owner_org_id', (v_ids->>'org')::bigint,
        'name', '[DEMO] Tài liệu trao đổi nội bộ',
        'description', 'Bộ sưu tập nội dung tổng hợp.',
        'visibility', 'organization',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('collection3', v_row->'collection_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection1')::bigint,
        'content_id', (v_ids->>'content5')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci1_5', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection1')::bigint,
        'content_id', (v_ids->>'content6')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci1_6', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection1')::bigint,
        'content_id', (v_ids->>'content7')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci1_7', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection1')::bigint,
        'content_id', (v_ids->>'content8')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 4,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci1_8', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection2')::bigint,
        'content_id', (v_ids->>'content5')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci2_5', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection2')::bigint,
        'content_id', (v_ids->>'content6')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci2_6', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection2')::bigint,
        'content_id', (v_ids->>'content7')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci2_7', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection2')::bigint,
        'content_id', (v_ids->>'content8')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 4,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci2_8', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection3')::bigint,
        'content_id', (v_ids->>'content5')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci3_5', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection3')::bigint,
        'content_id', (v_ids->>'content6')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 2,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci3_6', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection3')::bigint,
        'content_id', (v_ids->>'content7')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 3,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci3_7', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_items', jsonb_build_object(
        'collection_id', (v_ids->>'collection3')::bigint,
        'content_id', (v_ids->>'content8')::bigint,
        'note', 'Tài liệu minh họa.',
        'sort_no', 4,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('ci3_8', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_collection_access_grants', jsonb_build_object(
        'collection_id', (v_ids->>'collection1')::bigint,
        'user_id', 'DMSH0002',
        'permission', 'reader',
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('collectiongrant', v_row->'grant_id');
    v_row := pg_temp.showcase_put('community_outbox_events', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'event_type', 'content_published',
        'actor_user_id', 'DMSH0001',
        'content_id', (v_ids->>'content5')::bigint,
        'payload_json', '{"synthetic":true,"namespace":"demo-showcase-v1"}'::jsonb,
        'dedup_key', 'demo-showcase-v1-event',
        'event_status', 'processed',
        'available_at', v_now - interval '2 days',
        'attempt_count', 0,
        'processed_at', v_now - interval '2 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('event', v_row->'event_id');
    v_row := pg_temp.showcase_put('community_notifications', jsonb_build_object(
        'recipient_user_id', 'DMSH0002',
        'event_id', (v_ids->>'event')::bigint,
        'content_id', (v_ids->>'content5')::bigint,
        'read_at', v_now - interval '1 days',
        'delivery_status', 'suppressed',
        'delivery_mode', 'digest',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('notification', v_row->'notification_id');
    v_row := pg_temp.showcase_put('community_user_preferences', jsonb_build_object(
        'user_id', 'DMSH0002',
        'digest_frequency', 'off',
        'onboarding_completed_at', v_now - interval '5 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('preferences', v_row->'user_id');
    v_row := pg_temp.showcase_put('community_notification_digest_batches', jsonb_build_object(
        'user_id', 'DMSH0002',
        'window_start', v_now - interval '3 days',
        'window_end', v_now - interval '2 days',
        'batch_status', 'failed',
        'attempt_count', 3,
        'last_error_code', 'DEMO_NO_DELIVERY',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('digest', v_row->'batch_id');
    v_row := pg_temp.showcase_put('community_notification_digest_items', jsonb_build_object(
        'batch_id', (v_ids->>'digest')::bigint,
        'notification_id', (v_ids->>'notification')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('digestitem', v_row->'item_id');
    v_row := pg_temp.showcase_put('community_reports', jsonb_build_object(
        'reporter_user_id', 'DMSH0003',
        'content_id', (v_ids->>'content9')::bigint,
        'reason_code', 'outdated',
        'description', '[DEMO] Tình huống báo cáo đã được đóng, không có dữ liệu nhạy cảm.',
        'report_status', 'dismissed',
        'resolved_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('report', v_row->'report_id');
    v_row := pg_temp.showcase_put('community_moderation_actions', jsonb_build_object(
        'report_id', (v_ids->>'report')::bigint,
        'actor_user_id', 'DMSH0004',
        'action_type', 'dismiss',
        'reason', '[DEMO] Nội dung là ví dụ tổng hợp, kết thúc tình huống minh họa.',
        'previous_state_json', '{"reportStatus":"open"}'::jsonb,
        'new_state_json', '{"reportStatus":"dismissed"}'::jsonb,
        'occurred_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('moderation', v_row->'action_id');
    v_row := pg_temp.showcase_put('community_maintenance_reviews', jsonb_build_object(
        'content_id', (v_ids->>'content10')::bigint,
        'revision_id', (v_ids->>'crevision10')::bigint,
        'reviewer_user_id', 'DMSH0002',
        'outcome', 'current',
        'note', '[DEMO] Rà soát sổ tay mẫu.',
        'reviewed_at', v_now - interval '1 days',
        'next_review_due_at', (v_now + interval '30 days')::date,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('maintenance', v_row->'maintenance_review_id');
    v_row := pg_temp.showcase_put('community_contribution_events', jsonb_build_object(
        'org_id', (v_ids->>'org')::bigint,
        'user_id', 'DMSH0002',
        'content_id', (v_ids->>'content3')::bigint,
        'source_event_id', (v_ids->>'event')::bigint,
        'reason_code', 'accepted_answer',
        'points_delta', 10,
        'policy_version', 1,
        'occurred_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('contribution', v_row->'contribution_event_id');
    v_row := pg_temp.showcase_put('community_badges', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'code', 'DEMO-SHOWCASE-BADGE',
        'name', '[DEMO] Người chia sẻ kiến thức',
        'description', 'Huy hiệu tổng hợp, không phải thành tích thật.',
        'rule_version', 1,
        'rule_json', '{}'::jsonb,
        'status', 'retired',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('badge', v_row->'badge_id');
    v_row := pg_temp.showcase_put('community_badge_awards', jsonb_build_object(
        'badge_id', (v_ids->>'badge')::bigint,
        'user_id', 'DMSH0002',
        'status', 'active',
        'awarded_at', v_now - interval '1 days',
        'source_event_id', (v_ids->>'event')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('award', v_row->'award_id');
    v_row := pg_temp.showcase_put('community_learning_links', jsonb_build_object(
        'course_revision_id', (v_ids->>'revision1')::bigint,
        'lesson_id', (v_ids->>'lesson1_1')::bigint,
        'content_revision_id', (v_ids->>'crevision5')::bigint,
        'sort_no', 1,
        'status', 'active',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('learninglink', v_row->'learning_link_id');
    v_row := pg_temp.showcase_put('community_assets', jsonb_build_object(
        'owner_org_id', (v_ids->>'org')::bigint,
        'uploaded_by', 'DMSH0001',
        'original_name', 'DEMO-chua-co-tep.mp4',
        'mime_type', 'video/mp4',
        'size_bytes', 0,
        'media_type', 'video',
        'status', 'failed',
        'upload_id', gen_random_uuid(),
        'upload_total_bytes', 1024,
        'processing_attempts', 3,
        'processing_error_code', 'DEMO_NO_FILE',
        'cleanup_status', 'none',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('casset', v_row->'asset_id');
    v_row := pg_temp.showcase_put('community_asset_upload_parts', jsonb_build_object(
        'asset_id', (v_ids->>'casset')::bigint,
        'part_number', 1,
        'size_bytes', 1024,
        'checksum_sha256', repeat('a', 64),
        'part_status', 'reserved',
        'reservation_token', gen_random_uuid(),
        'reservation_expires_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cpart', v_row->'part_id');
    v_row := pg_temp.showcase_put('community_revision_assets', jsonb_build_object(
        'revision_id', (v_ids->>'crevision15')::bigint,
        'asset_id', (v_ids->>'casset')::bigint,
        'usage', 'primary_video',
        'caption', '[DEMO] Tệp chưa có, chỉ liên kết vào bản nháp.',
        'sort_no', 1,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('cassetlink', v_row->'revision_asset_id');
    v_row := pg_temp.showcase_put('community_media_transcripts', jsonb_build_object(
        'asset_id', (v_ids->>'casset')::bigint,
        'version_no', 1,
        'language', 'vi',
        'body_text', '[DEMO] Bản thảo do người viết, chưa có media để kiểm chứng.',
        'segments_json', '{}'::jsonb,
        'status', 'draft',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days',
        'client_request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64)
    ));
    v_ids := v_ids || jsonb_build_object('transcript', v_row->'transcript_id');
    v_row := pg_temp.showcase_put('community_search_chunks', jsonb_build_object(
        'content_id', (v_ids->>'content15')::bigint,
        'revision_id', (v_ids->>'crevision15')::bigint,
        'transcript_id', (v_ids->>'transcript')::bigint,
        'chunk_no', 0,
        'body_text', '[DEMO] Không có vector thật; không tham gia truy hồi.',
        'embedding_model', 'demo-not-indexed',
        'index_status', 'failed',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('searchchunk', v_row->'chunk_id');
    v_row := pg_temp.showcase_put('community_ai_turns', jsonb_build_object(
        'user_id', 'DMSH0002',
        'request_id', gen_random_uuid(),
        'request_hash', repeat('a', 64),
        'model', 'demo-no-model',
        'turn_status', 'failed',
        'source_count', 1,
        'duration_ms', 0,
        'completed_at', v_now - interval '1 days',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aiturn', v_row->'turn_id');
    v_row := pg_temp.showcase_put('community_ai_turn_sources', jsonb_build_object(
        'turn_id', (v_ids->>'aiturn')::bigint,
        'knowledge_chunk_id', (v_ids->>'documentchunk')::bigint,
        'citation_no', 1,
        'used_in_answer', false,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aisource', v_row->'source_id');
    v_row := pg_temp.showcase_put('community_ai_feedback', jsonb_build_object(
        'turn_id', (v_ids->>'aiturn')::bigint,
        'user_id', 'DMSH0002',
        'helpful', false,
        'reason_code', 'missing_source',
        'note', '[DEMO] Không gọi mô hình; bản ghi minh họa lỗi thiếu nguồn.',
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('aifeedback', v_row->'feedback_id');
    v_row := pg_temp.showcase_put('community_mentions', jsonb_build_object(
        'mentioned_user_id', 'DMSH0003',
        'comment_id', (v_ids->>'comment5')::bigint,
        'created_by', 'DMSH0001',
        'updated_by', 'DMSH0001',
        'create_time', v_now - interval '10 days',
        'update_time', v_now - interval '10 days'
    ));
    v_ids := v_ids || jsonb_build_object('mention', v_row->'mention_id');

    -- Đối chiếu số bảng và chụp fingerprint SAU mọi cập nhật con trỏ.
    SELECT count(DISTINCT table_name) INTO v_count FROM pg_temp.showcase_inserted;
    IF v_count <> 105 THEN RAISE EXCEPTION 'Chỉ có %/105 bảng được seed; rollback toàn bộ.', v_count; END IF;
    FOR v_item IN SELECT seq, table_name, key_json FROM pg_temp.showcase_inserted ORDER BY seq LOOP
        EXECUTE format('SELECT to_jsonb(t) FROM learning_hub.%I t,
                        jsonb_populate_record(NULL::learning_hub.%I, $1) k WHERE %s',
                       v_item.table_name, v_item.table_name,
                       pg_temp.showcase_where(v_item.table_name, 't'))
        INTO STRICT v_row USING v_item.key_json;
        v_manifest := v_manifest || jsonb_build_array(jsonb_build_object(
            'table', v_item.table_name, 'key', v_item.key_json, 'fingerprint', md5(v_row::text)));
    END LOOP;
    IF EXISTS (SELECT 1 FROM learning_hub.users WHERE org_id = (v_ids->>'org')::bigint
               AND (status <> 'locked' OR auth_provider <> 'SSO' OR password_hash IS NOT NULL)) THEN
        RAISE EXCEPTION 'Tài khoản DEMO không đúng trạng thái khóa; rollback.';
    END IF;
    INSERT INTO learning_hub.audit_log(actor_id, action, entity_type, entity_id, details)
    VALUES ('DMSH0001', 'DEMO_SHOWCASE_INSTALLED', 'DEMO', 'demo-showcase-v1', jsonb_build_object(
        'namespace', 'demo-showcase-v1', 'synthetic', true, 'installedAt', v_now,
        'databaseActor', current_user, 'orgId', v_ids->'org', 'tableCount', 105,
        'rowCount', jsonb_array_length(v_manifest), 'rows', v_manifest));
    RAISE NOTICE 'Đã tạo % dòng DEMO trong 105 bảng. Không có tệp READY hoặc job pending.',
                 jsonb_array_length(v_manifest);
END;
$seed$;
COMMIT;

-- Kết quả theo manifest (không đếm toàn bộ dữ liệu nghiệp vụ).
SELECT x->>'table' AS table_name, count(*) AS demo_rows
FROM learning_hub.audit_log a CROSS JOIN LATERAL jsonb_array_elements(a.details->'rows') x
WHERE a.action = 'DEMO_SHOWCASE_INSTALLED' AND details->>'namespace' = 'demo-showcase-v1'
GROUP BY x->>'table' ORDER BY table_name;
