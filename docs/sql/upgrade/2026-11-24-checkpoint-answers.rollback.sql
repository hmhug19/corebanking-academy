-- Xóa toàn bộ câu trả lời câu hỏi nhanh đã lưu. Chạy sau khi đã gỡ backend dùng bảng này.
BEGIN;

DROP TABLE IF EXISTS learning_hub.checkpoint_answers;

COMMIT;
