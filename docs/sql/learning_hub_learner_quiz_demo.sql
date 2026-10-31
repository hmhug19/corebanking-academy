-- Bài học loại quiz cho FE-DEMO-C1 và FE-DEMO-C2; PostgreSQL 14+.
-- Chạy thủ công toàn bộ file SAU Identity + Learning + Assessment + Learning demo + Assessment demo và sau
-- docs/sql/upgrade/2026-11-01-learner-quiz.sql nếu DB được tạo trước khi baseline có cột course_lessons.quiz_id.
-- Chỉ dev/test: không chạy trên production. Không sửa user, password, role, schema hoặc dữ liệu đã có.
-- Dùng ID tự tăng + RETURNING, không giả định ID khóa/bài học và không chỉnh sequence.
-- Chạy một lần: lần hai báo lỗi và rollback, không nhân đôi/ghi đè dữ liệu đã sửa.
--
-- Thêm vào chương cuối của phiên bản đã xuất bản của mỗi khóa một bài học bắt buộc loại quiz, trỏ tới đề FINAL_EXAM
-- của khóa. Sửa trực tiếp phiên bản đã xuất bản (thay vì xuất bản bản mới) để hocvien, đang học bản này, thấy bài quiz
-- ngay. Đạt đề cuối khóa sẽ hoàn thành bài quiz; khóa hoàn thành khi mọi bài bắt buộc khác cũng xong.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SELECT pg_advisory_xact_lock(7142051965);

DO $demo$
DECLARE
    v_actor varchar(20);
    v_course record;
    v_chapter bigint;
    v_quiz bigint;
    v_sort integer;
BEGIN
    -- 1. Resolve dữ liệu nền; dừng trước INSERT nếu thiếu hoặc đã chạy.
    SELECT u.user_id INTO STRICT v_actor FROM learning_hub.users u JOIN learning_hub.orgs o ON o.org_id = u.org_id
    WHERE u.user_name = 'giangvien' AND o.code = 'BIDV110B803' AND u.status = 'active' AND NOT u.must_change_password;
    IF (SELECT count(*) FROM learning_hub.courses c JOIN learning_hub.course_revisions r
            ON r.revision_id = c.published_revision_id
        WHERE c.code IN ('FE-DEMO-C1', 'FE-DEMO-C2') AND r.state = 'published') <> 2 THEN
        RAISE EXCEPTION 'Thiếu khóa published FE-DEMO-C1/C2. Chạy learning_hub_learning_demo.sql trước.';
    END IF;
    IF (SELECT count(*) FROM learning_hub.quizzes q JOIN learning_hub.courses c USING (course_id)
        WHERE c.code IN ('FE-DEMO-C1', 'FE-DEMO-C2') AND q.quiz_type = 'FINAL_EXAM' AND q.status = 'PUBLISHED'
          AND NOT q.is_deleted) <> 2 THEN
        RAISE EXCEPTION 'Thiếu đề FINAL_EXAM published của FE-DEMO-C1/C2. Chạy learning_hub_assessment_demo.sql trước.';
    END IF;
    IF EXISTS (SELECT 1 FROM learning_hub.course_lessons l
               JOIN learning_hub.course_chapters ch ON ch.chapter_id = l.chapter_id
               JOIN learning_hub.courses c ON c.published_revision_id = ch.revision_id
               WHERE c.code IN ('FE-DEMO-C1', 'FE-DEMO-C2') AND l.lesson_type = 'quiz') THEN
        RAISE EXCEPTION 'Đã có bài học loại quiz trong FE-DEMO-C1/C2. Không chạy lại hoặc ghi đè dữ liệu đã có.';
    END IF;

    -- 2. Một bài quiz bắt buộc ở cuối chương cuối của mỗi khóa.
    FOR v_course IN
        SELECT c.course_id, c.code, c.published_revision_id AS revision_id
        FROM learning_hub.courses c WHERE c.code IN ('FE-DEMO-C1', 'FE-DEMO-C2') ORDER BY c.code
    LOOP
        SELECT ch.chapter_id INTO STRICT v_chapter FROM learning_hub.course_chapters ch
        WHERE ch.revision_id = v_course.revision_id ORDER BY ch.sort_no DESC, ch.chapter_id DESC LIMIT 1;
        SELECT q.quiz_id INTO STRICT v_quiz FROM learning_hub.quizzes q
        WHERE q.course_id = v_course.course_id AND q.quiz_type = 'FINAL_EXAM' AND q.status = 'PUBLISHED'
          AND NOT q.is_deleted;
        SELECT coalesce(max(l.sort_no), 0) + 1 INTO v_sort FROM learning_hub.course_lessons l
        WHERE l.chapter_id = v_chapter;
        INSERT INTO learning_hub.course_lessons
            (chapter_id, title, lesson_type, estimated_duration_seconds, is_required, sort_no, quiz_id,
             created_by, updated_by)
        VALUES (v_chapter, 'Bài kiểm tra cuối khóa', 'quiz', 900, true, v_sort, v_quiz, v_actor, v_actor);
    END LOOP;
END;
$demo$;
COMMIT;

-- Mapping ID thực tế cho FE/Postman: không hardcode ID theo thứ tự tạo.
SELECT c.code AS course, l.lesson_id, l.title, l.is_required, l.quiz_id
FROM learning_hub.course_lessons l
JOIN learning_hub.course_chapters ch ON ch.chapter_id = l.chapter_id
JOIN learning_hub.courses c ON c.published_revision_id = ch.revision_id
WHERE c.code IN ('FE-DEMO-C1', 'FE-DEMO-C2') AND l.lesson_type = 'quiz'
ORDER BY c.code;
