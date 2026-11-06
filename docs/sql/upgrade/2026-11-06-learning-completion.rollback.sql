-- Dừng backend ghi tiến độ. Tính lại theo công thức cũ; không khôi phục snapshot dữ liệu lịch sử.
BEGIN;
LOCK TABLE learning_hub.course_enrollments, learning_hub.lesson_progress IN SHARE ROW EXCLUSIVE MODE;
WITH components AS (
    SELECT e.enrollment_id, e.user_id,
           coalesce((SELECT ca.assessment_revision_id FROM learning_hub.course_assessments ca
                     WHERE ca.revision_id = e.revision_id AND ca.placement = 'final' LIMIT 1),
                    (SELECT cr.final_quiz_id FROM learning_hub.course_completion_rules cr
                     WHERE cr.revision_id = e.revision_id LIMIT 1)) AS quiz_id,
           (SELECT count(*) FROM learning_hub.course_chapters ch
            JOIN learning_hub.course_lessons l USING (chapter_id)
            WHERE ch.revision_id = e.revision_id AND l.is_required) AS lessons,
           (SELECT coalesce(sum(coalesce(lp.progress_percent, 0)), 0)
            FROM learning_hub.course_chapters ch JOIN learning_hub.course_lessons l USING (chapter_id)
            LEFT JOIN learning_hub.lesson_progress lp ON lp.lesson_id = l.lesson_id AND lp.enrollment_id = e.enrollment_id
            WHERE ch.revision_id = e.revision_id AND l.is_required) AS progress,
           e.revision_id
    FROM learning_hub.course_enrollments e
), evaluated AS (
    SELECT c.*, CASE WHEN quiz_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM learning_hub.course_chapters ch JOIN learning_hub.course_lessons l USING (chapter_id)
        WHERE ch.revision_id = c.revision_id AND l.is_required AND l.quiz_id = c.quiz_id
    ) THEN 1 ELSE 0 END AS extra_final,
    EXISTS (SELECT 1 FROM learning_hub.quiz_attempts qa WHERE qa.user_id = c.user_id
            AND qa.enrollment_id = c.enrollment_id AND qa.quiz_id = c.quiz_id
            AND qa.passed AND qa.status IN ('SUBMITTED', 'AUTO_SUBMITTED')) AS passed
    FROM components c
)
UPDATE learning_hub.course_enrollments e
SET progress_percent = CASE WHEN x.lessons + x.extra_final = 0 THEN 0
    ELSE trunc((x.progress + CASE WHEN x.extra_final = 1 AND x.passed THEN 100 ELSE 0 END)
               / (x.lessons + x.extra_final), 3) END,
    row_version = e.row_version + 1, update_time = now()
FROM evaluated x WHERE x.enrollment_id = e.enrollment_id;
ALTER TABLE learning_hub.course_enrollments DROP CONSTRAINT IF EXISTS ck_enrollment_learning_progress;
COMMENT ON COLUMN learning_hub.course_enrollments.progress_percent IS 'Tiến độ tổng hợp gồm bài bắt buộc và bài thi cuối riêng.';
COMMIT;
