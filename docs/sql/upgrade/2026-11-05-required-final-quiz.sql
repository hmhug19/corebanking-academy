-- Chạy trước backend nhận finalQuizId. Các revision cũ giữ nguyên cấu hình và lịch sử hoàn thành.
BEGIN;
ALTER TABLE learning_hub.course_completion_rules ADD COLUMN IF NOT EXISTS final_quiz_id bigint;
COMMENT ON COLUMN learning_hub.course_completion_rules.final_quiz_id IS
    'Bài FINAL_EXAM bắt buộc đạt của revision; tham chiếu logic quizzes.quiz_id, backend kiểm cùng khóa.';
COMMIT;
