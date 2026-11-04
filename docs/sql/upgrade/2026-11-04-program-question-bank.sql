-- Chạy trước backend mới. Không chuyển câu hỏi cũ sang một chương trình suy đoán.
BEGIN;
ALTER TABLE learning_hub.questions ADD COLUMN IF NOT EXISTS program_id bigint;
ALTER TABLE learning_hub.questions ALTER COLUMN course_id DROP NOT NULL;
-- Mỗi câu giữ nguyên chương trình của revision mới nhất tại thời điểm nâng cấp.
UPDATE learning_hub.questions q
SET program_id = r.program_id
FROM (
    SELECT DISTINCT ON (course_id) course_id, program_id
    FROM learning_hub.course_revisions
    ORDER BY course_id, revision_no DESC
) r
WHERE q.course_id = r.course_id AND q.program_id IS NULL AND r.program_id IS NOT NULL;
COMMENT ON TABLE learning_hub.questions IS
    'Ngân hàng câu hỏi theo chương trình; câu đã phát đề chỉ xóa mềm hoặc INACTIVE.';
COMMENT ON COLUMN learning_hub.questions.program_id IS
    'Chương trình sở hữu ngân hàng (logic, không FK); không đổi theo revision của khóa sau khi tạo.';
COMMENT ON COLUMN learning_hub.questions.course_id IS
    'Nguồn khóa học của client cũ; NULL với câu hỏi tạo theo chương trình.';
CREATE INDEX IF NOT EXISTS ix_asm_questions_program
    ON learning_hub.questions (program_id, status) WHERE NOT is_deleted;
-- Dữ liệu chưa gắn chương trình vẫn giữ lại và chỉ đọc/sửa qua đường courseId cũ.
SELECT count(*) AS legacy_questions_without_program
FROM learning_hub.questions WHERE program_id IS NULL AND NOT is_deleted;
COMMIT;
