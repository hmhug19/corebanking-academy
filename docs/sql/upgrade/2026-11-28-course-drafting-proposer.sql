-- Trợ lý ngoài (ADR 0004): who makes the Đề xuất of a drafting session. 'assistant' is the in-house model (the worker
-- proposes each step, as before); 'external' is a Trợ lý ngoài acting through /api/v1/mcp: the worker never takes its
-- steps, no chat message is posted for it, and it has no source document (the server never receives the file), only
-- the name the author gave it, kept in source_name for display.
-- Run as dev_user (the backend's role) on a DB that already has 2026-11-24-course-drafting-sessions.sql. Safe to run
-- again. Existing sessions become 'assistant'. Run it BEFORE deploying the backend.
--   psql -h <host> -U dev_user -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-28-course-drafting-proposer.sql
-- Rollback: docs/sql/upgrade/2026-11-28-course-drafting-proposer.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.course_drafting_sessions
    ADD COLUMN IF NOT EXISTS proposer varchar(10) NOT NULL DEFAULT 'assistant',
    ADD COLUMN IF NOT EXISTS source_name varchar(255);
ALTER TABLE learning_hub.course_drafting_sessions DROP CONSTRAINT IF EXISTS ck_course_drafting_sessions_proposer;
ALTER TABLE learning_hub.course_drafting_sessions
    ADD CONSTRAINT ck_course_drafting_sessions_proposer CHECK (proposer IN ('assistant', 'external'));
COMMENT ON COLUMN learning_hub.course_drafting_sessions.proposer IS
    'Ai đề xuất các bước: assistant (model nội bộ, worker chạy) | external (Trợ lý ngoài qua /api/v1/mcp).';
COMMENT ON COLUMN learning_hub.course_drafting_sessions.source_name IS
    'Tên tài liệu tác giả đưa cho Trợ lý ngoài, chỉ để hiển thị; server không nhận tệp. NULL với phiên của trợ lý nội bộ.';

COMMIT;
