-- Kiểm chứng READ ONLY bộ demo-showcase-v1 sau khi chạy seed.
-- psql -X -v ON_ERROR_STOP=1 -d <database> -f learning_hub_showcase_v1_verify.sql
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '180s';
SET LOCAL TIME ZONE 'UTC';
SET LOCAL standard_conforming_strings = on;
DO $verify$
DECLARE
    v_details jsonb;
    v_entry jsonb;
    v_row jsonb;
    v_predicate text;
    v_table text;
    v_org bigint;
    v_count bigint;
    v_changed bigint := 0;
BEGIN
    SELECT details INTO STRICT v_details FROM learning_hub.audit_log
    WHERE action = 'DEMO_SHOWCASE_INSTALLED' AND details->>'namespace' = 'demo-showcase-v1';
    v_org := (v_details->>'orgId')::bigint;
    SELECT count(DISTINCT x->>'table') INTO v_count FROM jsonb_array_elements(v_details->'rows') x;
    IF v_count <> 105 THEN RAISE EXCEPTION 'Manifest chỉ có %/105 bảng.', v_count; END IF;
    FOR v_entry IN SELECT value FROM jsonb_array_elements(v_details->'rows') LOOP
        v_table := v_entry->>'table';
        SELECT string_agg(format('t.%I IS NOT DISTINCT FROM k.%I', a.attname, a.attname), ' AND '
                          ORDER BY a.attnum) INTO v_predicate
        FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
        WHERE i.indrelid = to_regclass('learning_hub.' || v_table) AND i.indisprimary;
        IF v_predicate IS NULL THEN RAISE EXCEPTION 'Thiếu bảng/PK: %', v_table; END IF;
        EXECUTE format('SELECT to_jsonb(t) FROM learning_hub.%I t,
                        jsonb_populate_record(NULL::learning_hub.%I, $1) k WHERE %s',
                       v_table, v_table, v_predicate)
        INTO v_row USING v_entry->'key';
        IF v_row IS NULL THEN RAISE EXCEPTION 'Thiếu dòng DEMO: % %', v_table, v_entry->'key'; END IF;
        IF md5(v_row::text) <> v_entry->>'fingerprint' THEN v_changed := v_changed + 1; END IF;
    END LOOP;
    IF (SELECT count(*) FROM learning_hub.users WHERE org_id = v_org) <> 12
       OR EXISTS (SELECT 1 FROM learning_hub.users WHERE org_id = v_org
                  AND (status <> 'locked' OR auth_provider <> 'SSO' OR password_hash IS NOT NULL)) THEN
        RAISE EXCEPTION 'Hồ sơ mẫu không còn đúng 12 tài khoản SSO khóa đăng nhập.';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.user_roles ur JOIN learning_hub.users u USING (user_id)
               WHERE u.org_id = v_org AND ur.active)
       OR EXISTS (SELECT 1 FROM learning_hub.community_moderators m WHERE m.org_id = v_org AND m.status = 'active') THEN
        RAISE EXCEPTION 'Có grant hoặc moderator active trong đơn vị DEMO; cần kiểm tra thủ công.';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.community_outbox_events WHERE owner_org_id = v_org
               AND event_status IN ('pending', 'processing'))
       OR EXISTS (SELECT 1 FROM learning_hub.community_assets WHERE owner_org_id = v_org
                  AND status IN ('uploading', 'completing', 'processing', 'ready')) THEN
        RAISE EXCEPTION 'Có media ready hoặc tác vụ Community có thể chạy trong đơn vị DEMO.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM learning_hub.course_enrollments e
        JOIN learning_hub.courses c USING (course_id)
        WHERE c.owner_org_id = v_org AND e.progress_percent < 0
    ) THEN RAISE EXCEPTION 'Tiến độ không hợp lệ.'; END IF;
    IF EXISTS (
        SELECT 1 FROM learning_hub.quiz_attempts a
        JOIN learning_hub.quizzes q USING (quiz_id)
        JOIN learning_hub.courses c ON c.course_id = q.course_id
        LEFT JOIN LATERAL (
            SELECT coalesce(sum(ans.score_awarded), 0) score,
                   count(*) FILTER (WHERE ans.is_correct) correct
            FROM learning_hub.attempt_questions aq
            JOIN learning_hub.attempt_answers ans USING (attempt_question_id)
            WHERE aq.attempt_id = a.attempt_id
        ) sums ON true
        WHERE c.owner_org_id = v_org
          AND (a.raw_score IS DISTINCT FROM sums.score OR a.correct_count IS DISTINCT FROM sums.correct
               OR a.score_percent IS DISTINCT FROM round(100 * sums.score / a.max_score, 2))
    ) THEN RAISE EXCEPTION 'Kết quả thi khác tổng điểm đáp án.'; END IF;
    RAISE NOTICE 'Đủ % dòng manifest, 105 bảng. Có % dòng đã thay đổi so với lần seed đầu.',
                 jsonb_array_length(v_details->'rows'), v_changed;
    IF v_changed > 0 THEN
        RAISE WARNING 'Dữ liệu DEMO đã được ứng dụng/người dùng cập nhật. Không chạy lại để khôi phục hoặc xóa tự động.';
    END IF;
END;
$verify$;
SELECT x->>'table' AS table_name, count(*) AS seeded_rows
FROM learning_hub.audit_log a CROSS JOIN LATERAL jsonb_array_elements(a.details->'rows') x
WHERE a.action = 'DEMO_SHOWCASE_INSTALLED' AND a.details->>'namespace' = 'demo-showcase-v1'
GROUP BY x->>'table' ORDER BY table_name;
COMMIT;
