-- Trợ lý ngoài (ADR 0004): what the author confirmed at each step of a session a Trợ lý ngoài drives, so
-- get_drafting_session can hand it back and the next step builds on the author's edits. Only listed values are kept:
-- course code, title, summary, level, objectives, programme, module and kind (step 1); chapter and lesson titles, never
-- lesson content (step 2); bonus points, competency and the kept questions (step 3); nothing for step 4. NULL for
-- steps not confirmed and for every step of an in-house session.
-- Run as dev_user (the backend's role) on a DB that already has 2026-11-28-course-drafting-proposer.sql. Safe to run
-- again. Run it BEFORE deploying the backend.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-29-course-drafting-confirmed.sql
-- Rollback: docs/sql/upgrade/2026-11-29-course-drafting-confirmed.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_drafting_steps ADD COLUMN IF NOT EXISTS confirmed jsonb;
COMMENT ON COLUMN learning_hub.course_drafting_steps.confirmed IS
    'Giá trị tác giả đã chốt ở bước này (phiên Trợ lý ngoài), để Trợ lý ngoài đọc lại; không chứa nội dung bài học.';

COMMIT;
