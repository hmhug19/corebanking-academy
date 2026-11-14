-- Run as dev_user after 2026-11-13-scorm-course-lessons.sql, before deploying the backend.
-- Existing revisions retain their completion policy; no learner results are rewritten.
BEGIN;
ALTER TABLE learning_hub.course_lessons
    ADD COLUMN IF NOT EXISTS scorm_completion_rule varchar(24);
UPDATE learning_hub.course_lessons
SET scorm_completion_rule = 'completed_or_passed'
WHERE lesson_type = 'scorm' AND scorm_completion_rule IS NULL;
COMMENT ON COLUMN learning_hub.course_lessons.scorm_completion_rule IS
    'SCORM status requirement: completed_or_passed (legacy default) or passed; null for other lessons';
-- Standard label only: preserve customized names, function codes, routes and role grants.
UPDATE learning_hub.functions
SET name = 'Quản lý bài kiểm tra'
WHERE code = 'QUIZ_MANAGEMENT' AND name = 'Quản lý bài thi';
COMMIT;
