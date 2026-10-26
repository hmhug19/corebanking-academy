-- Dữ liệu Learning tổng hợp cho FE; PostgreSQL 14+.
-- Chạy thủ công toàn bộ file SAU Identity + Learning + extension media/progress hiện tại.
-- Không chạy trên production. Không sửa user, password, role, schema hoặc dữ liệu đã có.
-- Dùng ID tự tăng + RETURNING, không giả định ID org/team/khóa và không chỉnh sequence.
-- Chạy một lần: lần hai báo lỗi và rollback, không nhân đôi/ghi đè dữ liệu FE đã sửa.
-- 23/26 bảng có dữ liệu. course_shares, video_watch_ranges, course_assessments giữ trống:
-- chưa có đơn vị nhận khác, video thật đã kiểm chứng và Quiz Engine tương ứng.
-- assets/block_assets chỉ chứa file FAILED ở khóa NHÁP để thử UI lỗi; không giả file READY.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SELECT pg_advisory_xact_lock(7142051963);

DO $demo$
DECLARE
    v_org bigint;
    v_actor varchar(20);
    v_users varchar(20)[];
    v_fields bigint[] := '{}';
    v_programs bigint[] := '{}';
    v_modules bigint[] := '{}';
    v_courses bigint[] := '{}';
    v_revisions bigint[] := '{}';
    v_id bigint;
    v_course bigint;
    v_revision bigint;
    v_chapter bigint;
    v_lesson bigint;
    v_block bigint;
    v_group bigint;
    v_assignment bigint;
    v_target bigint;
    v_recipient bigint;
    v_enrollment bigint;
    v_asset bigint;
    v_now timestamptz := transaction_timestamp();
    v_start timestamptz;
    v_due timestamptz;
    v_user record;
    v_row record;
    v_item record;
    v_i integer;
    v_j integer;
    v_completed integer;
    v_total integer;
    v_status text;
BEGIN
    -- 1. Resolve Identity theo mã/username; dừng trước INSERT nếu thiếu demo hoặc quyền.
    SELECT org_id INTO STRICT v_org FROM learning_hub.orgs
    WHERE code = 'BIDV110B803' AND status = 'active';
    SELECT user_id INTO STRICT v_actor FROM learning_hub.users
    WHERE user_name = 'giangvien' AND org_id = v_org AND status = 'active'
      AND NOT must_change_password;
    SELECT array_agg(user_id ORDER BY user_name) INTO v_users FROM learning_hub.users
    WHERE user_name IN ('hocvien', 'hocvien.tichhop', 'giangvien')
      AND org_id = v_org AND status = 'active' AND NOT must_change_password;
    IF cardinality(v_users) IS DISTINCT FROM 3 THEN
        RAISE EXCEPTION 'Thiếu 3 tài khoản demo active: hocvien, hocvien.tichhop, giangvien.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM unnest(v_users) u(user_id) WHERE NOT EXISTS (
            SELECT 1 FROM learning_hub.user_roles ur JOIN learning_hub.roles r USING (role_id)
            WHERE ur.user_id = u.user_id AND ur.active AND r.active
              AND r.code = 'LEARNER' AND ur.scope = 'OWN'
        )
    ) OR NOT EXISTS (
        SELECT 1 FROM learning_hub.user_roles ur JOIN learning_hub.roles r USING (role_id)
        WHERE ur.user_id = v_actor AND ur.active AND r.active AND r.code = 'INSTRUCTOR'
          AND ((ur.scope = 'ORG' AND ur.org_id = v_org) OR ur.scope = 'GLOBAL')
    ) THEN
        RAISE EXCEPTION 'Thiếu grant demo hợp lệ; script không tự cấp quyền.';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.courses WHERE code LIKE 'FE-DEMO-%')
       OR EXISTS (SELECT 1 FROM learning_hub.training_fields WHERE code LIKE 'FE-DEMO-%')
       OR EXISTS (SELECT 1 FROM learning_hub.training_programs WHERE code LIKE 'FE-DEMO-%')
       OR EXISTS (SELECT 1 FROM learning_hub.business_modules WHERE code LIKE 'FE-DEMO-%')
       OR EXISTS (SELECT 1 FROM learning_hub.learning_groups WHERE code LIKE 'FE-DEMO-%') THEN
        RAISE EXCEPTION 'Đã có mã FE-DEMO-*. Không chạy lại hoặc ghi đè dữ liệu đã có.';
    END IF;

    -- 2. Danh mục: 3 lĩnh vực, 4 chương trình, 6 phân hệ; toàn bộ published để dùng bộ lọc/wizard.
    FOR v_row IN SELECT * FROM (VALUES
        (1, 'Nghiệp vụ Core Banking'), (2, 'Vận hành và quy trình'), (3, 'Hội nhập và an toàn')
    ) s(n, name) LOOP
        INSERT INTO learning_hub.training_fields
            (owner_org_id, code, name, description, status, sort_no, created_by, updated_by)
        VALUES (v_org, 'FE-DEMO-F' || v_row.n, v_row.name, 'Danh mục tổng hợp cho FE.',
            'published', v_row.n, v_actor, v_actor) RETURNING field_id INTO v_id;
        v_fields := array_append(v_fields, v_id);
    END LOOP;
    FOR v_row IN SELECT * FROM (VALUES
        (1, 1, 'Nền tảng nghiệp vụ Core Banking'), (2, 2, 'Vận hành hệ thống Core Banking'),
        (3, 3, 'Hội nhập cán bộ mới'), (4, 3, 'An toàn thông tin')
    ) s(n, field_no, name) LOOP
        INSERT INTO learning_hub.training_programs
            (field_id, owner_org_id, code, name, description, status, sort_no, created_by, updated_by)
        VALUES (v_fields[v_row.field_no], v_org, 'FE-DEMO-P' || v_row.n, v_row.name,
            'Chương trình tổng hợp cho FE.', 'published', v_row.n, v_actor, v_actor)
        RETURNING program_id INTO v_id;
        v_programs := array_append(v_programs, v_id);
    END LOOP;
    FOR v_row IN SELECT * FROM (VALUES
        (1, 1, 'Tổng quan'), (2, 2, 'Quản lý thay đổi'), (3, 2, 'Vận hành EOD'),
        (4, 3, 'Hội nhập'), (5, 1, 'Tiền gửi'), (6, 3, 'Bảo mật')
    ) s(n, field_no, name) LOOP
        INSERT INTO learning_hub.business_modules
            (field_id, code, name, status, sort_no, created_by, updated_by)
        VALUES (v_fields[v_row.field_no], 'FE-DEMO-M' || v_row.n, v_row.name,
            'published', v_row.n, v_actor, v_actor) RETURNING module_id INTO v_id;
        v_modules := array_append(v_modules, v_id);
    END LOOP;

    -- 3. Khóa → revision → chương → bài → nhiều khối có thứ tự. Chữ/quy trình không phụ thuộc S3.
    FOR v_row IN SELECT * FROM (VALUES
        (1, 1, 'Tổng quan hệ thống Core Banking', 'basic', 'mandatory', 4),
        (2, 2, 'Quy trình quản lý thay đổi', 'intermediate', 'mandatory', 3),
        (3, 2, 'Vận hành cuối ngày EOD/COB', 'advanced', 'mandatory', 3),
        (4, 3, 'Văn hóa và quy chế làm việc', 'basic', 'mandatory', 2),
        (5, 1, 'Phân hệ tiền gửi', 'intermediate', 'optional', 2),
        (6, 4, 'An toàn thông tin - bản nháp', 'basic', 'optional', 1)
    ) s(n, program_no, title, level, requirement, lessons) LOOP
        INSERT INTO learning_hub.courses
            (code, owner_org_id, owner_user_id, lifecycle_status, created_by, updated_by)
        VALUES ('FE-DEMO-C' || v_row.n, v_org, v_actor, 'active', v_actor, v_actor)
        RETURNING course_id INTO v_course;
        INSERT INTO learning_hub.course_revisions
            (course_id, revision_no, state, program_id, module_id, title, summary, level,
             default_requirement, cover_icon, cover_color, published_at, published_by, created_by, updated_by)
        VALUES (v_course, 1, CASE WHEN v_row.n = 6 THEN 'draft' ELSE 'published' END,
            v_programs[v_row.program_no], v_modules[v_row.n], v_row.title,
            'Nội dung đào tạo tổng hợp để FE thử danh sách, bộ lọc và màn học.', v_row.level,
            v_row.requirement, 'book-open', '#E5F3F2',
            CASE WHEN v_row.n <> 6 THEN v_now - interval '10 days' END,
            CASE WHEN v_row.n <> 6 THEN v_actor END, v_actor, v_actor) RETURNING revision_id INTO v_revision;
        v_courses := array_append(v_courses, v_course);
        v_revisions := array_append(v_revisions, v_revision);
        IF v_row.n <> 6 THEN
            UPDATE learning_hub.courses SET published_revision_id = v_revision WHERE course_id = v_course;
        END IF;
        INSERT INTO learning_hub.course_objectives (revision_id, description, sort_no, created_by, updated_by)
        VALUES (v_revision, 'Hiểu khái niệm và phạm vi áp dụng.', 1, v_actor, v_actor),
            (v_revision, 'Thực hiện các bước theo quy trình mẫu.', 2, v_actor, v_actor);
        INSERT INTO learning_hub.course_completion_rules
            (revision_id, require_all_required_lessons, reward_points, created_by, updated_by)
        VALUES (v_revision, true, 0, v_actor, v_actor);
        FOR v_j IN 1..v_row.lessons LOOP
            IF v_j IN (1, 3) THEN
                INSERT INTO learning_hub.course_chapters (revision_id, title, sort_no, created_by, updated_by)
                VALUES (v_revision, CASE WHEN v_j = 1 THEN 'Kiến thức nền tảng' ELSE 'Thực hành' END,
                    CASE WHEN v_j = 1 THEN 1 ELSE 2 END, v_actor, v_actor) RETURNING chapter_id INTO v_chapter;
            END IF;
            INSERT INTO learning_hub.course_lessons
                (chapter_id, title, lesson_type, estimated_duration_seconds, is_required, sort_no, created_by, updated_by)
            VALUES (v_chapter, 'Bài ' || v_j || ': ' || v_row.title, 'content', 600, true,
                CASE WHEN v_j > 2 THEN v_j - 2 ELSE v_j END, v_actor, v_actor) RETURNING lesson_id INTO v_lesson;
            INSERT INTO learning_hub.lesson_blocks
                (lesson_id, block_type, title, body, body_format, completion_mode, is_required, sort_no,
                 created_by, updated_by)
            VALUES (v_lesson, 'text', 'Kiến thức cần nắm',
                '## ' || v_row.title || E'\nĐây là dữ liệu tổng hợp, không dùng xử lý giao dịch thật.\n'
                || 'Đọc nội dung rồi đánh dấu hoàn thành để thử tiến độ.', 'markdown', 'manual', true, 1,
                v_actor, v_actor);
            INSERT INTO learning_hub.lesson_blocks
                (lesson_id, block_type, title, completion_mode, is_required, sort_no, created_by, updated_by)
            VALUES (v_lesson, 'process', 'Quy trình thực hành mẫu', 'manual', true, 2, v_actor, v_actor)
            RETURNING block_id INTO v_block;
            INSERT INTO learning_hub.process_steps (block_id, title, description, sort_no, created_by, updated_by)
            VALUES (v_block, 'Chuẩn bị', 'Kiểm tra thông tin đầu vào trong môi trường thử nghiệm.', 1, v_actor, v_actor),
                (v_block, 'Thực hiện', 'Thực hiện thao tác theo hướng dẫn của bài.', 2, v_actor, v_actor),
                (v_block, 'Đối soát', 'Kiểm tra kết quả và ghi nhận điểm cần lưu ý.', 3, v_actor, v_actor);
        END LOOP;
    END LOOP;

    -- 4. File FAILED chỉ gắn vào khóa nháp. Muốn thử media thật phải upload qua API/worker.
    INSERT INTO learning_hub.assets
        (owner_org_id, uploaded_by, storage_type, original_name, mime_type, size_bytes,
         status, created_by, updated_by)
    VALUES (v_org, v_actor, 's3', 'FE-DEMO-video-upload-loi.mp4', 'video/mp4', 0,
        'failed', v_actor, v_actor) RETURNING asset_id INTO v_asset;
    INSERT INTO learning_hub.lesson_blocks
        (lesson_id, block_type, title, completion_mode, completion_threshold, is_required, sort_no,
         media_asset_id, created_by, updated_by)
    VALUES (v_lesson, 'video', 'Video lỗi - cần upload lại trước khi xuất bản', 'video_coverage', 90,
        false, 3, v_asset, v_actor, v_actor) RETURNING block_id INTO v_block;
    INSERT INTO learning_hub.block_assets (block_id, asset_id, usage, caption, sort_no, created_by, updated_by)
    VALUES (v_block, v_asset, 'primary', 'File lỗi có chủ đích để thử UI, không có object trong S3.',
        1, v_actor, v_actor);

    -- 5. Một nhóm chứa ba người; giao theo snapshot, không phụ thuộc nhóm thay đổi sau này.
    INSERT INTO learning_hub.learning_groups
        (owner_org_id, code, name, description, status, created_by, updated_by)
    VALUES (v_org, 'FE-DEMO-G1', 'Nhóm học thử FE', 'Ba tài khoản demo đã có LEARNER OWN.',
        'active', v_actor, v_actor) RETURNING group_id INTO v_group;
    INSERT INTO learning_hub.learning_group_members
        (group_id, user_id, joined_at, added_by, created_by, updated_by)
    SELECT v_group, u, v_now - interval '7 days', v_actor, v_actor, v_actor FROM unnest(v_users) u;

    -- 6. Bốn đợt giao: gần hạn, chưa bắt đầu, quá hạn, đã hoàn thành.
    FOR v_i IN 1..4 LOOP
        v_start := v_now - interval '7 days';
        v_due := v_now + CASE v_i WHEN 1 THEN interval '2 days' WHEN 2 THEN interval '14 days'
            WHEN 3 THEN interval '-1 day' ELSE interval '-3 days' END;
        INSERT INTO learning_hub.course_assignments
            (course_id, revision_id, assigning_org_id, assigned_by, title, start_at, due_at,
             requirement, enrollment_mode, reminder_enabled, reminder_days_before, status, activated_at,
             idempotency_key, request_hash, created_by, updated_by)
        VALUES (v_courses[v_i], v_revisions[v_i], v_org, v_actor, 'FE-DEMO đợt giao ' || v_i,
            v_start, v_due, 'mandatory', 'continue', true, 3, 'active', v_start,
            'FE-DEMO-ASSIGN-' || v_i, repeat('0', 64), v_actor, v_actor) RETURNING assignment_id INTO v_assignment;
        -- Hash không đại diện request API: không dùng key FE-DEMO-ASSIGN-* để retry POST assignment.
        INSERT INTO learning_hub.assignment_targets
            (assignment_id, target_type, group_id, include_descendants, target_name_snapshot, created_by, updated_by)
        VALUES (v_assignment, 'learning_group', v_group, false, 'Nhóm học thử FE', v_actor, v_actor)
        RETURNING target_id INTO v_target;
        FOR v_user IN SELECT user_id, org_id, team_id FROM learning_hub.users WHERE user_id = ANY(v_users) LOOP
            -- Hoàn thành theo grain bài/khối: khóa 1 = 50%, khóa 2/3 = 0%, khóa 4 = 100%.
            v_completed := CASE v_i WHEN 1 THEN 2 WHEN 4 THEN 2 ELSE 0 END;
            v_total := CASE v_i WHEN 1 THEN 4 WHEN 4 THEN 2 ELSE 3 END;
            v_status := CASE WHEN v_completed = v_total THEN 'completed'
                WHEN v_completed > 0 THEN 'in_progress' ELSE 'not_started' END;
            INSERT INTO learning_hub.course_enrollments
                (course_id, revision_id, user_id, cycle_no, enrollment_origin, org_id_snapshot, team_id_snapshot,
                 enrolled_at, started_at, last_access_at, completed_at, status, progress_percent, created_by, updated_by)
            VALUES (v_courses[v_i], v_revisions[v_i], v_user.user_id, 1, 'assigned', v_user.org_id, v_user.team_id,
                v_start, CASE WHEN v_completed > 0 THEN v_start + interval '1 day' END,
                CASE WHEN v_completed > 0 THEN v_now - interval '4 days' END,
                CASE WHEN v_completed = v_total THEN v_now - interval '4 days' END,
                v_status, 100.0 * v_completed / v_total, v_actor, v_actor) RETURNING enrollment_id INTO v_enrollment;
            INSERT INTO learning_hub.assignment_recipients
                (assignment_id, user_id, enrollment_id, org_id_snapshot, team_id_snapshot, resolved_at,
                 effective_start_at, effective_due_at, requirement, status, created_by, updated_by)
            VALUES (v_assignment, v_user.user_id, v_enrollment, v_user.org_id, v_user.team_id, v_start,
                v_start, v_due, 'mandatory', 'active', v_actor, v_actor) RETURNING recipient_id INTO v_recipient;
            INSERT INTO learning_hub.recipient_target_sources (recipient_id, target_id, created_by, updated_by)
            VALUES (v_recipient, v_target, v_actor, v_actor);
            INSERT INTO learning_hub.learning_reminders
                (recipient_id, scheduled_at, due_at_snapshot, channel, status, sent_at,
                 attempt_count, idempotency_key, created_by, updated_by)
            VALUES (v_recipient, v_due - interval '3 days', v_due, 'in_app',
                CASE WHEN v_completed = v_total THEN 'cancelled' ELSE 'pending' END, NULL, 0,
                'FE-DEMO-REMINDER-' || v_recipient, v_actor, v_actor);
            FOR v_item IN
                SELECT l.lesson_id FROM learning_hub.course_lessons l
                JOIN learning_hub.course_chapters c USING (chapter_id) WHERE c.revision_id = v_revisions[v_i]
                ORDER BY c.sort_no, l.sort_no LIMIT v_completed
            LOOP
                INSERT INTO learning_hub.lesson_progress
                    (enrollment_id, lesson_id, status, progress_percent, started_at, last_access_at, completed_at,
                     created_by, updated_by)
                VALUES (v_enrollment, v_item.lesson_id, 'completed', 100, v_start + interval '1 day',
                    v_now - interval '4 days', v_now - interval '4 days', v_actor, v_actor);
                INSERT INTO learning_hub.block_progress
                    (enrollment_id, block_id, status, progress_percent, started_at, last_access_at, completed_at,
                     last_event_sequence, created_by, updated_by)
                SELECT v_enrollment, b.block_id, 'completed', 100, v_start + interval '1 day',
                    v_now - interval '4 days', v_now - interval '4 days', 1, v_actor, v_actor
                FROM learning_hub.lesson_blocks b WHERE b.lesson_id = v_item.lesson_id;
                UPDATE learning_hub.course_enrollments SET last_lesson_id = v_item.lesson_id,
                    last_block_id = (SELECT max(block_id) FROM learning_hub.lesson_blocks
                        WHERE lesson_id = v_item.lesson_id) WHERE enrollment_id = v_enrollment;
            END LOOP;
        END LOOP;
    END LOOP;

    -- 7. Tự đăng ký khóa tùy chọn; không có assignment/recipient/hạn hoàn thành.
    INSERT INTO learning_hub.course_enrollments
        (course_id, revision_id, user_id, cycle_no, enrollment_origin, self_enrolled_at,
         org_id_snapshot, team_id_snapshot, enrolled_at, status, progress_percent, created_by, updated_by)
    SELECT v_courses[5], v_revisions[5], user_id, 1, 'self', v_now,
        org_id, team_id, v_now, 'not_started', 0, v_actor, v_actor
    FROM learning_hub.users WHERE user_id = ANY(v_users);
END;
$demo$;
COMMIT;

-- Mapping ID thực tế cho FE/Postman: không hardcode ID theo thứ tự tạo.
SELECT c.code, c.course_id, r.revision_id, r.title, r.state
FROM learning_hub.courses c JOIN learning_hub.course_revisions r USING (course_id)
WHERE c.code LIKE 'FE-DEMO-%' ORDER BY c.code;
SELECT u.user_name, e.enrollment_id, c.code, e.status, e.progress_percent
FROM learning_hub.course_enrollments e JOIN learning_hub.users u USING (user_id)
JOIN learning_hub.courses c USING (course_id) WHERE c.code LIKE 'FE-DEMO-%'
ORDER BY u.user_name, c.code;
