BEGIN;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM learning_hub.course_completion_rules WHERE final_quiz_id IS NOT NULL) THEN
        RAISE EXCEPTION 'Còn cấu hình bài thi cuối khóa; không tự xóa điều kiện hoàn thành khi rollback.';
    END IF;
END $$;
ALTER TABLE learning_hub.course_completion_rules DROP COLUMN IF EXISTS final_quiz_id;
COMMIT;
