-- Dữ liệu demo cho mốc video và transcript: chỉ dùng trên DB local, KHÔNG chạy trên máy chủ dùng chung.
-- Điền khối video của khóa DEMO-MEDIA (video mẫu 30 giây): 3 mốc tại 0, 10, 20 giây và 6 đoạn transcript trong 30 giây.
-- Chỉ ghi khi cả hai cột còn NULL nên chạy lại an toàn và không đè nội dung đã sửa. Chỉ đổi nội dung khối: không đụng
-- hồ sơ học, tiến độ hay lượt thi. Cần đã chạy docs/sql/upgrade/2026-11-03-lesson-player-modes.sql.
--   psql -h localhost -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/demo-media/lesson-player-modes-demo.sql
BEGIN;

UPDATE learning_hub.lesson_blocks
SET video_chapters = '[
        {"startSeconds": 0, "title": "Giới thiệu"},
        {"startSeconds": 10, "title": "Khái niệm chính"},
        {"startSeconds": 20, "title": "Tổng kết"}
    ]'::jsonb,
    transcript = '[
        {"startSeconds": 0, "text": "Xin chào, đây là video giới thiệu về hệ thống Core Banking."},
        {"startSeconds": 5, "text": "Hôm nay chúng ta làm quen với các phân hệ chính của lõi ngân hàng."},
        {"startSeconds": 10, "text": "Phân hệ tiền gửi quản lý tài khoản, lãi suất và kỳ hạn."},
        {"startSeconds": 15, "text": "Phân hệ tín dụng theo dõi hồ sơ vay, giải ngân và thu nợ."},
        {"startSeconds": 20, "text": "Mọi giao dịch cuối cùng đều được ghi nhận vào sổ cái."},
        {"startSeconds": 25, "text": "Cảm ơn bạn đã theo dõi, hẹn gặp lại ở bài sau."}
    ]'::jsonb
WHERE block_id = 33
  AND block_type = 'video'
  AND video_chapters IS NULL
  AND transcript IS NULL;

COMMIT;
