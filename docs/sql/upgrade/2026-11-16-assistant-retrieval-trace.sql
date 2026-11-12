-- Retrieval trace of an assistant turn: for every search of the turn, the tool, the number of candidates, and the chunks
-- given to the model (kind, chunk id, rank, similarity). Ids and numbers only; never question, answer or chunk text.
-- Run once on a DB that already has 2026-11-10-assistant-conversations.sql. Safe to run again: it only adds what is missing.
-- The backend refuses to start without this column (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-16-assistant-retrieval-trace.sql
-- Rollback: docs/sql/upgrade/2026-11-16-assistant-retrieval-trace.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.assistant_turns ADD COLUMN IF NOT EXISTS retrieval_trace jsonb NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN learning_hub.assistant_turns.retrieval_trace IS 'Dấu vết truy xuất của lượt: mỗi lần tìm gồm công cụ, số ứng viên và các đoạn đã đưa cho mô hình (loại document|lesson, chunk id, hạng, độ tương đồng). Chỉ ID và số, không có văn bản. Xoá cùng hội thoại.';

COMMIT;
