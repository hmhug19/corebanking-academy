-- Dừng backend ghi tiến độ trước khi chạy; triển khai cùng BE/FE theo contract mới.
-- Không đổi status/completed_at hoặc kết quả thi đã ghi nhận. Chạy lại an toàn.
BEGIN;
LOCK TABLE learning_hub.course_enrollments, learning_hub.lesson_progress IN SHARE ROW EXCLUSIVE MODE;

WITH progress AS (
    SELECT e.enrollment_id,
           coalesce(trunc(avg(coalesce(lp.progress_percent, 0)) FILTER (WHERE l.lesson_id IS NOT NULL), 3), 0) AS percent
    FROM learning_hub.course_enrollments e
    LEFT JOIN learning_hub.course_chapters ch ON ch.revision_id = e.revision_id
    LEFT JOIN learning_hub.course_lessons l ON l.chapter_id = ch.chapter_id AND l.is_required
    LEFT JOIN learning_hub.lesson_progress lp ON lp.enrollment_id = e.enrollment_id AND lp.lesson_id = l.lesson_id
    GROUP BY e.enrollment_id
)
UPDATE learning_hub.course_enrollments e
SET progress_percent = p.percent, row_version = e.row_version + 1, update_time = now()
FROM progress p
WHERE p.enrollment_id = e.enrollment_id AND e.progress_percent IS DISTINCT FROM p.percent;

ALTER TABLE learning_hub.course_enrollments DROP CONSTRAINT IF EXISTS ck_enrollment_learning_progress;
ALTER TABLE learning_hub.course_enrollments ADD CONSTRAINT ck_enrollment_learning_progress
    CHECK (progress_percent BETWEEN 0 AND 100);
COMMENT ON COLUMN learning_hub.course_enrollments.progress_percent IS
    'Tiến độ học: trung bình tiến độ bài bắt buộc, không cộng bài thi cuối riêng; 100% không đồng nghĩa hoàn thành khóa.';
COMMIT;
