-- Migration GIỚI HẠN cho dữ liệu showcase tổng hợp. KHÔNG tự chạy trên server.
-- Chuyển DEMO-SHOWCASE-TEAM sang DEMO-SHOWCASE-OPS cùng thành viên và khóa do nhóm sở hữu.
-- Giữ ID, bài học, bài thi, lượt giao, điểm, tiến độ và snapshot tổ chức/nhóm lịch sử.
-- Dừng toàn bộ backend/worker/ghi; backup DB và thử restore, chạy preflight, diễn tập trên bản sao.
-- Cần rà mọi tham chiếu ngoài các bảng dưới đây (bao gồm JSON, SCORM, Community, Knowledge, S3,
-- chia sẻ, câu hỏi, tích hợp, cache và báo cáo) trước khi chạy. Script KHÔNG tự đảm bảo các tham chiếu này.
-- Chỉ xử lý các đối tượng có mã DEMO-SHOWCASE; từ chối nếu phát hiện dữ liệu không thuộc demo,
-- grant đang hoạt động khác OWN, chương trình/lĩnh vực dùng chung, hoặc khóa tham chiếu media.
-- Khôi phục bằng bản backup DB đã thử; không tự đảo các cập nhật sau migration.
-- psql -X -v ON_ERROR_STOP=1 -f <file>, role dev_user có quyền cần thiết.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5min';
SELECT pg_advisory_xact_lock(7142051963);
LOCK TABLE learning_hub.orgs, learning_hub.teams, learning_hub.users,
    learning_hub.user_roles, learning_hub.courses, learning_hub.course_revisions,
    learning_hub.training_programs, learning_hub.training_fields IN SHARE ROW EXCLUSIVE MODE;

DO $migration$
DECLARE
    v_source bigint;
    v_target bigint;
    v_team bigint;
    v_count bigint;
    v_courses bigint[];
    v_programs bigint[];
    v_fields bigint[];
BEGIN
    SELECT count(*), min(org_id) INTO v_count, v_source FROM learning_hub.orgs
    WHERE code = 'DEMO-SHOWCASE-V1' AND org_type = 'UNIT' AND status = 'active';
    IF v_count <> 1 THEN RAISE EXCEPTION 'Không tìm thấy UNIT nguồn demo duy nhất đang active'; END IF;
    SELECT count(*), min(org_id) INTO v_count, v_target FROM learning_hub.orgs
    WHERE code = 'DEMO-SHOWCASE-OPS' AND org_type = 'DEPARTMENT' AND status = 'active' AND parent_id = v_source;
    IF v_count <> 1 THEN RAISE EXCEPTION 'Không tìm thấy phòng đích demo duy nhất thuộc UNIT nguồn'; END IF;
    SELECT count(*), min(team_id) INTO v_count, v_team FROM learning_hub.teams
    WHERE code = 'DEMO-SHOWCASE-TEAM';
    IF v_count <> 1 THEN RAISE EXCEPTION 'Không tìm thấy nhóm demo duy nhất'; END IF;
    IF NOT EXISTS (SELECT 1 FROM learning_hub.teams WHERE team_id = v_team AND org_id = v_source AND status = 'active') THEN
        RAISE EXCEPTION 'Nhóm không active tại UNIT nguồn, hoặc đã chuyển; không chạy lại';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.teams WHERE org_id = v_target AND team_id <> v_team
               AND lower(code) = lower('DEMO-SHOWCASE-TEAM')) THEN
        RAISE EXCEPTION 'Mã nhóm đã tồn tại trong phòng đích';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.users WHERE team_id = v_team
               AND (org_id IS DISTINCT FROM v_source OR user_id !~ '^DMSH[0-9]{4}$')) THEN
        RAISE EXCEPTION 'Nhóm có thành viên không thuộc nguồn hoặc ngoài tập tài khoản showcase';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.user_roles r WHERE r.active
               AND ((r.user_id IN (SELECT user_id FROM learning_hub.users WHERE team_id = v_team) AND r.scope <> 'OWN')
                    OR r.team_id = v_team)) THEN
        RAISE EXCEPTION 'Có grant đang hoạt động khác OWN hoặc grant TEAM; phải rà và xử lý qua quy trình phân quyền';
    END IF;
    SELECT coalesce(array_agg(course_id), ARRAY[]::bigint[]) INTO v_courses FROM learning_hub.courses
    WHERE owner_team_id = v_team;
    IF EXISTS (SELECT 1 FROM learning_hub.courses WHERE course_id = ANY(v_courses)
               AND (owner_org_id <> v_source OR code NOT LIKE 'DEMO-SHOWCASE-%')) THEN
        RAISE EXCEPTION 'Nhóm sở hữu khóa ngoài phạm vi demo hoặc khác đơn vị nguồn';
    END IF;
    SELECT coalesce(array_agg(DISTINCT program_id), ARRAY[]::bigint[]) INTO v_programs
    FROM learning_hub.course_revisions WHERE course_id = ANY(v_courses) AND program_id IS NOT NULL;
    IF EXISTS (SELECT 1 FROM unnest(v_programs) id
               WHERE NOT EXISTS (SELECT 1 FROM learning_hub.training_programs p
                   WHERE p.program_id = id AND p.owner_org_id = v_source AND p.code LIKE 'DEMO-SHOWCASE-%')) THEN
        RAISE EXCEPTION 'Chương trình không tồn tại hoặc ngoài phạm vi demo';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.course_revisions r
               WHERE r.program_id = ANY(v_programs) AND NOT (r.course_id = ANY(v_courses))) THEN
        RAISE EXCEPTION 'Chương trình dùng chung với khóa ngoài tập chuyển; cần thiết kế riêng';
    END IF;
    SELECT coalesce(array_agg(DISTINCT field_id), ARRAY[]::bigint[]) INTO v_fields
    FROM learning_hub.training_programs WHERE program_id = ANY(v_programs);
    IF EXISTS (SELECT 1 FROM unnest(v_fields) id
               WHERE NOT EXISTS (SELECT 1 FROM learning_hub.training_fields f
                   WHERE f.field_id = id AND f.owner_org_id = v_source AND f.code LIKE 'DEMO-SHOWCASE-%')) THEN
        RAISE EXCEPTION 'Lĩnh vực không tồn tại hoặc ngoài phạm vi demo';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.training_programs p
               WHERE p.field_id = ANY(v_fields) AND NOT (p.program_id = ANY(v_programs))) THEN
        RAISE EXCEPTION 'Lĩnh vực dùng chung với chương trình ngoài tập chuyển; cần thiết kế riêng';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.course_revisions r WHERE r.course_id = ANY(v_courses)
               AND r.cover_asset_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM learning_hub.lesson_blocks b
                  JOIN learning_hub.course_lessons l ON l.lesson_id = b.lesson_id
                  JOIN learning_hub.course_chapters ch ON ch.chapter_id = l.chapter_id
                  JOIN learning_hub.course_revisions r ON r.revision_id = ch.revision_id
                  WHERE r.course_id = ANY(v_courses) AND b.media_asset_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM learning_hub.block_assets ba
                  JOIN learning_hub.lesson_blocks b ON b.block_id = ba.block_id
                  JOIN learning_hub.course_lessons l ON l.lesson_id = b.lesson_id
                  JOIN learning_hub.course_chapters ch ON ch.chapter_id = l.chapter_id
                  JOIN learning_hub.course_revisions r ON r.revision_id = ch.revision_id
                  WHERE r.course_id = ANY(v_courses)) THEN
        RAISE EXCEPTION 'Khóa có tài nguyên: phải rà quyền và phương án chuyển tài nguyên riêng';
    END IF;
    RAISE NOTICE 'Chuyển nhóm %, từ org % sang %, số khóa %, chương trình %, lĩnh vực %',
        v_team, v_source, v_target, cardinality(v_courses), cardinality(v_programs), cardinality(v_fields);
    UPDATE learning_hub.teams SET org_id = v_target WHERE team_id = v_team;
    UPDATE learning_hub.users SET org_id = v_target, auth_ver = auth_ver + 1, update_time = now()
    WHERE team_id = v_team AND org_id = v_source;
    UPDATE learning_hub.training_fields SET owner_org_id = v_target, row_version = row_version + 1, update_time = now()
    WHERE field_id = ANY(v_fields);
    UPDATE learning_hub.training_programs SET owner_org_id = v_target, row_version = row_version + 1, update_time = now()
    WHERE program_id = ANY(v_programs);
    UPDATE learning_hub.courses SET owner_org_id = v_target, row_version = row_version + 1, update_time = now()
    WHERE course_id = ANY(v_courses);
END
$migration$;

SELECT t.team_id, t.code, t.org_id, o.name, count(u.user_id) AS members
FROM learning_hub.teams t JOIN learning_hub.orgs o ON o.org_id = t.org_id
LEFT JOIN learning_hub.users u ON u.team_id = t.team_id AND u.org_id = t.org_id
WHERE t.code = 'DEMO-SHOWCASE-TEAM' GROUP BY t.team_id, t.code, t.org_id, o.name;
COMMIT;
