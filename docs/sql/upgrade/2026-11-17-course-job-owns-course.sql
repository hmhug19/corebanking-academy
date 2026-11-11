-- A course generation job now owns a draft course: the job keeps course_id / revision_id and writes the outline, lessons
-- and proposed questions into that revision. A job can also be stopped (status 'stopped'). A lesson of an AI course
-- carries ai_state (pending | writing | written | user_edited); null means the lesson is not AI managed.
-- Run once on a DB that already has 2026-11-12-course-generation-jobs.sql and 2026-11-13-course-revision-ai-generated.sql.
-- Safe to run again. The backend reads these columns at the first request: run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-17-course-job-owns-course.sql
-- Rollback: docs/sql/upgrade/2026-11-17-course-job-owns-course.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_generation_jobs ADD COLUMN IF NOT EXISTS course_id bigint;
ALTER TABLE learning_hub.course_generation_jobs ADD COLUMN IF NOT EXISTS revision_id bigint;
COMMENT ON COLUMN learning_hub.course_generation_jobs.course_id IS
    'Khóa học nháp do job tạo cùng lúc với job và ghi nội dung vào; null với job tạo trước thay đổi này.';
COMMENT ON COLUMN learning_hub.course_generation_jobs.revision_id IS 'Bản nháp đầu tiên của khóa học trên; job ghi dàn ý, bài học vào đây.';

ALTER TABLE learning_hub.course_generation_jobs DROP CONSTRAINT IF EXISTS course_generation_jobs_status_check;
ALTER TABLE learning_hub.course_generation_jobs ADD CONSTRAINT course_generation_jobs_status_check CHECK (
    status IN ('queued', 'running', 'waiting_approval', 'completed', 'failed', 'cancelled', 'stopped'));
COMMENT ON COLUMN learning_hub.course_generation_jobs.status IS
    'queued | running | waiting_approval | completed | failed | cancelled | stopped (người dùng dừng, giữ các bài đã viết).';

CREATE INDEX IF NOT EXISTS idx_course_generation_jobs_revision
    ON learning_hub.course_generation_jobs (revision_id) WHERE revision_id IS NOT NULL;

ALTER TABLE learning_hub.course_lessons ADD COLUMN IF NOT EXISTS ai_state varchar(12);
ALTER TABLE learning_hub.course_lessons DROP CONSTRAINT IF EXISTS ck_course_lessons_ai_state;
ALTER TABLE learning_hub.course_lessons ADD CONSTRAINT ck_course_lessons_ai_state CHECK (
    ai_state IS NULL OR ai_state IN ('pending', 'writing', 'written', 'user_edited'));
COMMENT ON COLUMN learning_hub.course_lessons.ai_state IS
    'Bài do AI viết: pending | writing | written | user_edited (người dùng đã sửa, job không ghi đè); null nếu bài không do AI quản lý.';

COMMIT;
