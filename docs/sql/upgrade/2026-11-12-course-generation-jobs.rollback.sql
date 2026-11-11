-- Rollback of 2026-11-12-course-generation-jobs.sql: drops the generation jobs and their steps (the generated drafts
-- and the uploaded source documents stay; delete the documents from the knowledge screen if they are not wanted).
BEGIN;
SET LOCAL lock_timeout = '5s';

DROP TABLE IF EXISTS learning_hub.course_generation_steps;
DROP TABLE IF EXISTS learning_hub.course_generation_jobs;

COMMIT;
