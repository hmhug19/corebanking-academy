-- Reviewing 👎 answers with the user's consent: the feedback row may hold a copy of the turn it rates (question, answer,
-- citations, skill, prompt version, retrieval trace), and a new menu function AI_FEEDBACK_REVIEW lists those copies.
-- The copy belongs to the feedback row: unticking the share box nulls it, deleting the conversation or the retention job
-- deletes the row with it. AI_FEEDBACK_REVIEW is granted to no role; an operator grants it by hand to named reviewers.
-- Run once on a DB that already has 2026-11-10-assistant-feedback.sql. Safe to run again: it only adds what is missing.
-- The backend refuses to start without these columns (ddl-auto=validate): run it BEFORE deploying the backend.
--   psql -h <host> -U <user> -d <database> -v ON_ERROR_STOP=1 -f docs/sql/upgrade/2026-11-21-assistant-feedback-review.sql
-- Rollback: docs/sql/upgrade/2026-11-21-assistant-feedback-review.rollback.sql
BEGIN;
SET LOCAL lock_timeout = '5s';

ALTER TABLE learning_hub.assistant_feedback
    ADD COLUMN IF NOT EXISTS shared boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS shared_time timestamptz,
    ADD COLUMN IF NOT EXISTS shared_question text,
    ADD COLUMN IF NOT EXISTS shared_answer text,
    ADD COLUMN IF NOT EXISTS shared_citations jsonb,
    ADD COLUMN IF NOT EXISTS shared_skill varchar(40),
    ADD COLUMN IF NOT EXISTS shared_prompt_version varchar(40),
    ADD COLUMN IF NOT EXISTS shared_retrieval_trace jsonb;

COMMENT ON COLUMN learning_hub.assistant_feedback.shared IS 'TRUE khi người dùng tick "Gửi kèm câu hỏi và câu trả lời cho đội phát triển"; chỉ khi 👎. Mặc định FALSE.';
COMMENT ON COLUMN learning_hub.assistant_feedback.shared_time IS 'Lúc người dùng gửi bản sao; NULL khi shared = FALSE.';
COMMENT ON COLUMN learning_hub.assistant_feedback.shared_question IS 'Bản sao câu hỏi của lượt, chỉ khi shared; xoá khi bỏ tick, cùng hội thoại và theo lịch xoá hội thoại.';
COMMENT ON COLUMN learning_hub.assistant_feedback.shared_answer IS 'Bản sao câu trả lời của lượt, chỉ khi shared.';
COMMENT ON COLUMN learning_hub.assistant_feedback.shared_citations IS 'Bản sao trích dẫn của lượt, chỉ khi shared.';
COMMENT ON COLUMN learning_hub.assistant_feedback.shared_skill IS 'Kỹ năng (skill) đã trả lời lượt, chỉ khi shared; dùng để lọc.';
COMMENT ON COLUMN learning_hub.assistant_feedback.shared_prompt_version IS 'Phiên bản prompt của lượt, chỉ khi shared.';
COMMENT ON COLUMN learning_hub.assistant_feedback.shared_retrieval_trace IS 'Dấu vết truy xuất của lượt (chỉ ID và số), chỉ khi shared.';

ALTER TABLE learning_hub.assistant_feedback DROP CONSTRAINT IF EXISTS ck_assistant_feedback_shared_needs_thumbs_down;
ALTER TABLE learning_hub.assistant_feedback ADD CONSTRAINT ck_assistant_feedback_shared_needs_thumbs_down
    CHECK (shared = false OR helpful = false);
ALTER TABLE learning_hub.assistant_feedback DROP CONSTRAINT IF EXISTS ck_assistant_feedback_unshared_has_no_copy;
ALTER TABLE learning_hub.assistant_feedback ADD CONSTRAINT ck_assistant_feedback_unshared_has_no_copy
    CHECK (shared = true OR (shared_time IS NULL AND shared_question IS NULL AND shared_answer IS NULL
        AND shared_citations IS NULL AND shared_skill IS NULL AND shared_prompt_version IS NULL
        AND shared_retrieval_trace IS NULL));

CREATE INDEX IF NOT EXISTS idx_assistant_feedback_shared
    ON learning_hub.assistant_feedback(shared_time DESC) WHERE shared;

INSERT INTO learning_hub.functions(code, name, route_pattern, sort_no)
SELECT v.code, v.name, v.route_pattern, v.sort_no
FROM (VALUES ('AI_FEEDBACK_REVIEW', 'Xem phản hồi 👎 được chia sẻ', NULL::varchar, 156)) AS v(code, name, route_pattern, sort_no)
WHERE NOT EXISTS (SELECT 1 FROM learning_hub.functions f WHERE f.code = v.code);

COMMIT;
