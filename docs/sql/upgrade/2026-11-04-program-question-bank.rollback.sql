-- Không tự gán khóa cho câu hỏi chương trình hoặc xóa pool để rollback.
BEGIN;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM learning_hub.questions WHERE course_id IS NULL) THEN
        RAISE EXCEPTION 'Còn câu hỏi không gắn khóa; cần kế hoạch bảo toàn dữ liệu trước rollback.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM learning_hub.quiz_questions qq
        JOIN learning_hub.questions q ON q.question_id = qq.question_id
        JOIN learning_hub.quizzes z ON z.quiz_id = qq.quiz_id
        WHERE q.course_id IS DISTINCT FROM z.course_id
    ) THEN
        RAISE EXCEPTION 'Còn bộ đề dùng chung giữa các khóa; backend cũ không hỗ trợ rollback này.';
    END IF;
END $$;
ALTER TABLE learning_hub.questions ALTER COLUMN course_id SET NOT NULL;
DROP INDEX IF EXISTS learning_hub.ix_asm_questions_program;
ALTER TABLE learning_hub.questions DROP COLUMN program_id;
COMMENT ON TABLE learning_hub.questions IS 'Ngân hàng câu hỏi theo khóa học; câu đã phát đề chỉ xóa mềm hoặc INACTIVE.';
COMMENT ON COLUMN learning_hub.questions.course_id IS 'ID khóa học (logic, không FK); quyết định phạm vi quản lý câu hỏi.';
COMMIT;
