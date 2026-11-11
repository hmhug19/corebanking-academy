-- Rollback of 2026-11-11-course-content-index.sql. Drops the course index; it can be rebuilt by re-running the upgrade
-- and the backend worker, which indexes every published revision again. Deploy a backend without the course index first.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-11-course-content-index.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP INDEX IF EXISTS learning_hub.idx_course_content_index_course;
DROP TABLE IF EXISTS learning_hub.course_content_index;
DROP INDEX IF EXISTS learning_hub.idx_course_content_chunks_embedding;
DROP INDEX IF EXISTS learning_hub.idx_course_content_chunks_lesson;
DROP INDEX IF EXISTS learning_hub.idx_course_content_chunks_course;
DROP INDEX IF EXISTS learning_hub.uq_course_content_chunks_revision;
DROP TABLE IF EXISTS learning_hub.course_content_chunks;

COMMIT;
